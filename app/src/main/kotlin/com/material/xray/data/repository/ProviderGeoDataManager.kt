package com.material.xray.data.repository

import android.content.Context
import com.material.xray.core.common.connection.ConnectionStateCoordinator
import com.material.xray.core.common.di.ApplicationScope
import com.material.xray.core.common.log.LogBuffer
import com.material.xray.core.common.log.LogSource
import com.material.xray.core.network.AppHttpClient
import com.material.xray.core.xray.GEOIP_FILE_NAME
import com.material.xray.core.xray.GEOSITE_FILE_NAME
import com.material.xray.core.xray.PROVIDER_GEO_DATA_DOWNLOAD_SUFFIX
import com.material.xray.core.xray.PROVIDER_GEO_DATA_FILE_SUFFIX
import com.material.xray.core.xray.ProviderGeoDataFailure
import com.material.xray.core.xray.ProviderGeoDataResolution
import com.material.xray.core.xray.ProviderGeoDataState
import com.material.xray.core.xray.isProviderGeoDataFileName
import com.material.xray.core.xray.providerGeoDataFileName
import com.material.xray.core.xray.providerGeoDataNeedsDownload
import com.material.xray.core.xray.providerGeoDataUrls
import com.material.xray.core.xray.readGeoDataCodes
import com.material.xray.core.xray.resolveProviderGeoData
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.model.ConnectionState
import com.material.xray.model.RoutingRule
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.koin.core.annotation.Singleton

/**
 * Keeps the geodata that subscription routing profiles ship with, next to the app's own files and
 * apart from its settings. Downloads never block a connection: a missing file only puts the rules
 * that need it in compatibility mode until it arrives.
 */
@Singleton
class ProviderGeoDataManager(
    private val context: Context,
    private val httpClient: AppHttpClient,
    private val subscriptionDao: SubscriptionDao,
    private val settingsRepository: SettingsRepository,
    private val connectionStateCoordinator: ConnectionStateCoordinator,
    private val log: LogBuffer,
    @ApplicationScope private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val binaryDir get() = File(context.filesDir, "bin")
    private val syncMutex = Mutex()
    private val failures = mutableMapOf<String, ProviderGeoDataFailure>()
    private val codeCache = mutableMapOf<String, CachedCodes>()
    private val _state = MutableStateFlow(ProviderGeoDataState())

    val state: StateFlow<ProviderGeoDataState> = _state.asStateFlow()

    init {
        scope.launch(ioDispatcher) { publishAvailableFiles() }
    }

    /**
     * Fetches provider files whenever the set of referenced URLs changes (a subscription is added,
     * refreshed or deleted) and whenever a connection comes up, so they can travel through the tunnel.
     */
    suspend fun keepUpToDate(): Unit = coroutineScope {
        launch {
            connectionStateCoordinator.state
                .map { it is ConnectionState.Connected }
                .distinctUntilChanged()
                .filter { it }
                .collect { refresh() }
        }
        launch {
            combine(
                subscriptionDao.observeAll(),
                settingsRepository.subscriptionRoutingRules,
                settingsRepository.customRoutingRules,
            ) { subscriptions, subscriptionRules, customRules ->
                referencedUrls(subscriptions, subscriptionRules + customRules)
            }
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }

    fun refreshInBackground(force: Boolean = false) {
        scope.launch { refresh(force) }
    }

    /** Downloads missing or stale files, honouring the retry delay unless [force]d, and deletes unused ones. */
    suspend fun refresh(force: Boolean = false) = withContext(ioDispatcher) {
        syncMutex.withLock {
            binaryDir.mkdirs()
            val urls = referencedUrls()
            // ActiveCoreHttpClient sends the app's requests through Xray while a connection is up.
            val throughTunnel = connectionStateCoordinator.state.value is ConnectionState.Connected
            urls.forEach { url ->
                val file = File(binaryDir, providerGeoDataFileName(url))
                val lastModified = file.takeIf(File::isFile)?.lastModified()
                val now = System.currentTimeMillis()
                if (providerGeoDataNeedsDownload(lastModified, failures[url], now, force, throughTunnel)) {
                    download(url, file, throughTunnel)
                }
            }
            val keep = urls.mapTo(mutableSetOf(), ::providerGeoDataFileName)
            binaryDir.listFiles()
                ?.filter { file ->
                    val name = file.name.removeSuffix(PROVIDER_GEO_DATA_DOWNLOAD_SUFFIX)
                    isProviderGeoDataFileName(name) && (name !in keep || file.name.endsWith(PROVIDER_GEO_DATA_DOWNLOAD_SUFFIX))
                }
                ?.forEach { file ->
                    if (file.delete() && file.name.endsWith(PROVIDER_GEO_DATA_FILE_SUFFIX)) {
                        log.append(LogSource.APP, "Removed unused provider routing data ${file.name}")
                    }
                }
            publishAvailableFiles()
        }
    }

    internal suspend fun resolve(rules: List<RoutingRule>): ProviderGeoDataResolution = withContext(ioDispatcher) {
        if (rules.none { it.geoData != null }) {
            _state.update { it.copy(activeUnavailableUrls = emptySet()) }
            return@withContext ProviderGeoDataResolution(rules)
        }
        val resolution = resolveProviderGeoData(
            rules = rules,
            defaultGeoipCodes = codesOf(File(binaryDir, GEOIP_FILE_NAME)).orEmpty(),
            defaultGeositeCodes = codesOf(File(binaryDir, GEOSITE_FILE_NAME)).orEmpty(),
            providerCodes = { url -> codesOf(File(binaryDir, providerGeoDataFileName(url))) },
        )
        _state.update { it.copy(activeUnavailableUrls = resolution.unavailableUrls) }
        resolution
    }

    private suspend fun referencedUrls(): Set<String> = referencedUrls(
        subscriptionDao.getAll(),
        settingsRepository.subscriptionRoutingRules.first() + settingsRepository.customRoutingRules.first(),
    )

    private fun referencedUrls(subscriptions: List<SubscriptionEntity>, activeRules: List<RoutingRule>): Set<String> = (subscriptions.flatMap { it.toSubscriptionRouting()?.rules.orEmpty() } + activeRules).providerGeoDataUrls()

    private suspend fun download(url: String, target: File, throughTunnel: Boolean) {
        _state.update { it.copy(downloading = it.downloading + url) }
        val temporary = File(binaryDir, "${target.name}$PROVIDER_GEO_DATA_DOWNLOAD_SUFFIX")
        val route = if (throughTunnel) "through the tunnel" else "directly"
        val failure = try {
            fetch(url, temporary)
            val codes = readGeoDataCodes(temporary)
            when {
                codes.isEmpty() -> "the file has no categories"
                !temporary.renameTo(target) -> "unable to install ${target.name}"
                else -> {
                    log.append(LogSource.APP, "Provider routing data downloaded $route from $url (${codes.size} categories)")
                    null
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            error.message ?: error.javaClass.simpleName
        } catch (error: IllegalArgumentException) {
            error.message ?: error.javaClass.simpleName
        } finally {
            temporary.delete()
            _state.update { it.copy(downloading = it.downloading - url) }
        }
        if (failure == null) {
            failures.remove(url)
        } else {
            failures[url] = ProviderGeoDataFailure(System.currentTimeMillis(), throughTunnel)
            log.append(LogSource.APP, "Provider routing data download $route from $url failed: $failure")
        }
    }

    private suspend fun fetch(url: String, destination: File) = httpClient.use { client ->
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            destination.outputStream().use { output -> response.body.byteStream().copyTo(output) }
        }
    }

    private fun publishAvailableFiles() {
        val names = binaryDir.listFiles()
            ?.filter { it.isFile && isProviderGeoDataFileName(it.name) }
            ?.mapTo(mutableSetOf(), File::getName)
            .orEmpty()
        _state.update { it.copy(availableFiles = names) }
    }

    private fun codesOf(file: File): Set<String>? {
        if (!file.isFile) return null
        synchronized(codeCache) {
            codeCache[file.name]
                ?.takeIf { it.length == file.length() && it.lastModified == file.lastModified() }
                ?.let { return it.codes }
        }
        val codes = try {
            readGeoDataCodes(file)
        } catch (_: IOException) {
            return null
        }
        synchronized(codeCache) {
            codeCache[file.name] = CachedCodes(file.length(), file.lastModified(), codes)
        }
        return codes
    }

    private data class CachedCodes(val length: Long, val lastModified: Long, val codes: Set<String>)
}
