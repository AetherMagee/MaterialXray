package com.material.xray.core.xray

import com.material.xray.core.root.process.destroyForciblyCompat
import com.material.xray.core.root.process.waitForCompat
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Android Xray build both runtimes launch, plus the helper that hands it a TUN interface in
 * root mode. That is the core the installer extracted into the native library directory, unless
 * the user selected one from [XrayCoreStore].
 */
class XrayBinary(
    private val paths: XrayPaths,
) {
    private val binaryDir = File(paths.filesDir, "bin")
    private val cores = XrayCoreStore(paths)

    /** The core's own file, which a root shell executes directly. */
    val binaryPath: String? get() = cores.selectedExecutable()?.absolutePath ?: bundledPath

    /** The command that starts the core as this app's own uid, to be followed by Xray's arguments. */
    val userCommand: List<String>?
        get() = cores.selectedExecutable()?.let(::xrayUserCommand) ?: bundledPath?.let(::listOf)
    val rootLauncherPath: String? get() = nativeExecutablePath(ROOT_LAUNCHER_LIBRARY_NAME)
    private val bundledPath: String? get() = nativeExecutablePath(XRAY_EXECUTABLE_NAME)

    fun ensureAvailable(): Boolean {
        File(binaryDir, CORE_SOCKET_DIR).mkdirs()
        removeLegacyRootBinary()
        return binaryPath != null && rootLauncherPath != null
    }

    /** The version of the core that would run now. */
    fun readVersion(): String? {
        binaryDir.mkdirs()
        return readXrayVersion(userCommand ?: return null, binaryDir)
    }

    /** The version of the core shipped in the APK, regardless of the selection. */
    fun readBundledVersion(): String? {
        binaryDir.mkdirs()
        return readXrayVersion(listOf(bundledPath ?: return null), binaryDir)
    }

    fun configPath(): String = File(paths.filesDir, ACTIVE_CONFIG_FILE).absolutePath

    fun readConfig(): String? = File(configPath())
        .takeIf { it.isFile }
        ?.let { config -> runCatching { config.readText() }.getOrNull() }

    fun writeConfig(configJson: String) {
        File(paths.filesDir, ACTIVE_CONFIG_FILE).writeText(configJson)
    }

    /**
     * The config the user edited by hand, used verbatim in place of a generated one. Null when no
     * override is stored.
     */
    fun readOverrideConfig(): String? = File(paths.filesDir, ACTIVE_CONFIG_OVERRIDE_FILE)
        .takeIf { it.isFile }
        ?.let { override -> runCatching { override.readText() }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

    private fun nativeExecutablePath(name: String): String? = paths.nativeLibraryDir
        ?.resolve(name)
        ?.takeIf { it.isFile && it.canExecute() }
        ?.absolutePath

    // Versions that ran root mode on a separate Linux build extracted it here, along with the
    // install stamp that versioned it. Nothing reads either any more. The CA bundle moved into the
    // working directory, which is all the root-mode core can see; moving it keeps the cached
    // bundle, which can take long to rebuild on a throttled device.
    private fun removeLegacyRootBinary() {
        File(binaryDir, "xray").delete()
        File(binaryDir, "version").delete()
        val legacyBundle = File(paths.filesDir, LEGACY_CERTIFICATE_BUNDLE_NAME)
        val bundle = File(binaryDir, LEGACY_CERTIFICATE_BUNDLE_NAME)
        if (bundle.exists() || !legacyBundle.renameTo(bundle)) legacyBundle.delete()
    }

    private companion object {
        private const val ROOT_LAUNCHER_LIBRARY_NAME = "libxrayroot.so"
        private const val LEGACY_CERTIFICATE_BUNDLE_NAME = "xray-ca-certificates.pem"
    }
}

private val XRAY_VERSION_REGEX = Regex("^Xray\\s+v?([^\\s]+)")

/** The core's file name, which is also the process name `pidof` finds it by. */
const val XRAY_EXECUTABLE_NAME = "libxray.so"

const val ACTIVE_CONFIG_FILE = "config.json"

/** Sibling of [ACTIVE_CONFIG_FILE] holding a hand-edited config that replaces generation. */
internal const val ACTIVE_CONFIG_OVERRIDE_FILE = "config_override.json"

internal fun parseXrayVersion(output: String): String? = output.lineSequence()
    .mapNotNull { line -> XRAY_VERSION_REGEX.find(line.trim())?.groupValues?.getOrNull(1) }
    .firstOrNull()

/** Runs `[command] version` in [workingDir] and returns the version it reports, without a `v`. */
fun readXrayVersion(command: List<String>, workingDir: File, timeoutSeconds: Long = VERSION_TIMEOUT_SECONDS): String? = runCatching {
    val process = ProcessBuilder(command + "version")
        .directory(workingDir)
        .redirectErrorStream(true)
        .apply {
            environment()["xray.location.asset"] = workingDir.absolutePath
            environment()["XRAY_LOCATION_ASSET"] = workingDir.absolutePath
        }
        .start()

    if (!process.waitForCompat(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForciblyCompat()
        return@runCatching null
    }

    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (process.exitValue() == 0) parseXrayVersion(output) else null
}.getOrNull()

private const val VERSION_TIMEOUT_SECONDS = 2L
