package com.material.xray.core.data.xraycore

import com.material.xray.core.common.di.ApplicationScope
import com.material.xray.core.common.log.LogBuffer
import com.material.xray.core.common.log.LogSource
import com.material.xray.core.common.platform.PlatformInfo
import com.material.xray.core.data.repository.githubMirrorUrls
import com.material.xray.core.network.AppHttpClient
import com.material.xray.core.xray.InstalledXrayCore
import com.material.xray.core.xray.XrayBinary
import com.material.xray.core.xray.XrayCoreSource
import com.material.xray.core.xray.XrayCoreStore
import com.material.xray.core.xray.XrayPaths
import com.material.xray.core.xray.matchesAbi
import com.material.xray.core.xray.readElfHeader
import com.material.xray.core.xray.readXrayVersion
import com.material.xray.core.xray.xrayUserCommand
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Singleton

/** The install running in the background, or how the last one ended. */
sealed interface XrayCoreOperation {
    data class Downloading(val tag: String, val downloaded: Long, val total: Long?) : XrayCoreOperation

    data object Verifying : XrayCoreOperation

    data class Installed(val core: InstalledXrayCore) : XrayCoreOperation

    data class Failed(val failure: XrayCoreFailure) : XrayCoreOperation
}

data class XrayCoreState(
    /** Whether the bundled core's version and the installed cores have been read. */
    val loaded: Boolean = false,
    /** The version of the core inside the APK; null until read or when it cannot run. */
    val bundledVersion: String? = null,
    val installed: List<InstalledXrayCore> = emptyList(),
    /** The installed core that replaces the bundled one, or null for the bundled core. */
    val selectedId: String? = null,
    val operation: XrayCoreOperation? = null,
) {
    val isBusy: Boolean get() = operation is XrayCoreOperation.Downloading || operation == XrayCoreOperation.Verifying

    /** The version of the core that runs on the next start. */
    val activeVersion: String? get() = installed.firstOrNull { it.id == selectedId }?.version ?: bundledVersion
}

/**
 * Downloads upstream releases or takes a user's own build, verifies them and lets the user choose
 * which core runs. Installs run in the application scope so they survive leaving the screen; only
 * one runs at a time. A selection takes effect when the core next starts.
 */
@Singleton
class XrayCoreManager(
    private val paths: XrayPaths,
    private val httpClient: AppHttpClient,
    private val platformInfo: PlatformInfo,
    private val log: LogBuffer,
    @ApplicationScope private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val store = XrayCoreStore(paths)
    private val workingDir get() = File(paths.filesDir, "bin").apply { mkdirs() }
    private val _state = MutableStateFlow(XrayCoreState())
    private var job: Job? = null

    val state: StateFlow<XrayCoreState> = _state.asStateFlow()

    /** Whether upstream publishes builds this device can download; otherwise only files can be installed. */
    val canDownload: Boolean get() = xrayReleaseAssetName(platformInfo.primaryAbi) != null

    init {
        scope.launch(ioDispatcher) {
            store.removeAbandonedStaging()
            val bundledVersion = XrayBinary(paths).readBundledVersion()
            publishInstalled()
            _state.update { it.copy(loaded = true, bundledVersion = bundledVersion) }
        }
    }

    /** Upstream releases this device can run, newest first. */
    suspend fun releases(): List<XrayCoreRelease> = withContext(ioDispatcher) {
        try {
            httpClient.use { client -> fetchXrayCoreReleases(client, platformInfo.primaryAbi) }
        } catch (error: IOException) {
            log.append(LogSource.APP, "Listing Xray releases failed: ${error.describe()}")
            throw error as? XrayCoreException ?: XrayCoreException(XrayCoreFailure.Network, error)
        }
    }

    fun install(release: XrayCoreRelease) = launchOperation {
        val stagingDir = store.createStagingDir()
        stage(stagingDir) {
            val archive = File(stagingDir, "release.zip")
            httpClient.use { client ->
                downloadVerified(client, githubMirrorUrls(release.assetUrl), release.assetSha256, archive) { downloaded, total ->
                    _state.update { it.copy(operation = XrayCoreOperation.Downloading(release.tag, downloaded, total)) }
                }
            }
            _state.update { it.copy(operation = XrayCoreOperation.Verifying) }
            val executable = store.stagedExecutable(stagingDir)
            extractXrayExecutable(archive, executable)
            archive.delete()
            val version = verifyExecutable(executable)
            if (version != normalizeXrayVersion(release.tag)) throw XrayCoreException(XrayCoreFailure.Broken)
            store.commit(stagingDir, InstalledXrayCore(release.tag, version, release.assetSha256, XrayCoreSource.Release))
        }
    }

    /** Installs the executable, or release zip, that [open] reads. */
    fun installFromFile(open: () -> InputStream) = launchOperation {
        _state.update { it.copy(operation = XrayCoreOperation.Verifying) }
        val stagingDir = store.createStagingDir()
        stage(stagingDir) {
            val upload = File(stagingDir, "upload")
            val executable = store.stagedExecutable(stagingDir)
            var sha256 = open().use { input -> copyHashing(input, upload, MAX_UPLOAD_BYTES) }
            if (isZip(upload)) {
                sha256 = extractXrayExecutable(upload, executable)
                upload.delete()
            } else if (!upload.renameTo(executable)) {
                throw IOException("Could not stage the core")
            }
            val version = verifyExecutable(executable)
            store.commit(stagingDir, InstalledXrayCore("file-${sha256.take(FILE_ID_HASH_CHARS)}", version, sha256, XrayCoreSource.File))
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Forgets how the last install ended. */
    fun clearResult() {
        _state.update { state -> if (state.isBusy) state else state.copy(operation = null) }
    }

    /** Selects installed core [id], or the bundled core when null. */
    suspend fun select(id: String?) = withContext(ioDispatcher) {
        store.select(id)
        publishInstalled()
    }

    suspend fun delete(id: String) = withContext(ioDispatcher) {
        store.delete(id)
        publishInstalled()
    }

    private fun launchOperation(block: suspend () -> InstalledXrayCore) {
        if (_state.value.isBusy) return
        job = scope.launch(ioDispatcher) {
            val result = try {
                XrayCoreOperation.Installed(block())
            } catch (error: CancellationException) {
                _state.update { it.copy(operation = null) }
                throw error
            } catch (error: IOException) {
                log.append(LogSource.APP, "Installing an Xray core failed: ${error.describe()}")
                XrayCoreOperation.Failed((error as? XrayCoreException)?.failure ?: XrayCoreFailure.Network)
            }
            (result as? XrayCoreOperation.Installed)?.let { log.append(LogSource.APP, "Installed Xray core ${it.core.id} (${it.core.version})") }
            publishInstalled()
            _state.update { it.copy(operation = result) }
        }
    }

    private suspend fun <T> stage(stagingDir: File, block: suspend () -> T): T = try {
        block()
    } finally {
        if (stagingDir.exists()) stagingDir.deleteRecursively()
    }

    /** Checks [executable] is a core this device can run, and returns its version. */
    private fun verifyExecutable(executable: File): String {
        if (readElfHeader(executable)?.matchesAbi(platformInfo.primaryAbi) != true) {
            throw XrayCoreException(XrayCoreFailure.Unsupported)
        }
        executable.setReadable(true, true)
        executable.setExecutable(true, true)
        val version = readXrayVersion(xrayUserCommand(executable), workingDir, VERSION_TIMEOUT_SECONDS)
        val failure = when {
            version == null -> XrayCoreFailure.Broken
            compareXrayVersions(version, MINIMUM_XRAY_VERSION)?.let { it < 0 } == true -> XrayCoreFailure.TooOld
            else -> return version
        }
        throw XrayCoreException(failure)
    }

    private fun publishInstalled() {
        val installed = store.installed()
        val selectedId = store.selectedId()?.takeIf { id -> installed.any { it.id == id } }
        _state.update { it.copy(installed = installed, selectedId = selectedId) }
    }

    private fun Throwable.describe(): String = generateSequence(this) { it.cause }
        .joinToString(": ") { it.message ?: it.javaClass.simpleName }

    private companion object {
        // The first start of a freshly written 40 MB executable can be slow on older phones.
        const val VERSION_TIMEOUT_SECONDS = 15L
        const val MAX_UPLOAD_BYTES = 256L * 1024 * 1024
        const val FILE_ID_HASH_CHARS = 12
    }
}
