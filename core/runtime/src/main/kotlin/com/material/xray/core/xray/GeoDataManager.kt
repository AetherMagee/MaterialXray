package com.material.xray.core.xray

import android.content.Context
import com.material.xray.core.network.AppHttpClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.koin.core.annotation.Singleton

internal fun normalizeGeoDataUrl(url: String): String = url.trim()

internal fun geoDataUpdatedAt(targetFile: File, updatedAtFile: File): Long? {
    if (!targetFile.isFile) return null
    return updatedAtFile.takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull()?.takeIf { it > 0L }
        ?: targetFile.lastModified().takeIf { it > 0L }
}

internal fun seedBundledGeoData(
    configuredUrl: String,
    defaultUrl: String,
    targetFile: File,
    sourceFile: File,
    openAsset: () -> InputStream,
) {
    if (configuredUrl != defaultUrl || targetFile.exists()) return

    val temporary = File(targetFile.parentFile, "${targetFile.name}.bundled")
    try {
        openAsset().use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        if (!temporary.renameTo(targetFile)) {
            throw IOException("Unable to install bundled ${targetFile.name}")
        }
        sourceFile.writeText(defaultUrl)
    } finally {
        temporary.delete()
    }
}

data class GeoDataDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
) {
    val fraction: Float?
        get() = totalBytes
            ?.takeIf { it > 0L }
            ?.let { total -> (bytesDownloaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat() }
}

fun combinedGeoDataDownloadProgress(
    progress: Collection<GeoDataDownloadProgress>,
): GeoDataDownloadProgress? {
    if (progress.isEmpty()) return null
    val totalBytes = progress
        .takeIf { entries -> entries.all { it.totalBytes != null && it.totalBytes > 0L } }
        ?.sumOf { requireNotNull(it.totalBytes) }
    return GeoDataDownloadProgress(
        bytesDownloaded = progress.sumOf(GeoDataDownloadProgress::bytesDownloaded),
        totalBytes = totalBytes,
    )
}

enum class GeoDataAsset(val fileName: String, val displayName: String) {
    GEOIP(GEOIP_FILE_NAME, "GeoIP"),
    GEOSITE(GEOSITE_FILE_NAME, "GeoSite"),
}

@Singleton
class GeoDataManager(
    private val context: Context,
    private val httpClient: AppHttpClient,
    private val urlSettings: GeoDataUrlSettings,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val binaryDir get() = File(context.filesDir, "bin")
    private val geoipSourceFile get() = File(binaryDir, "geoip-source")
    private val geositeSourceFile get() = File(binaryDir, "geosite-source")
    private val geoipUpdatedAtFile get() = File(binaryDir, "geoip-updated-at")
    private val geositeUpdatedAtFile get() = File(binaryDir, "geosite-updated-at")
    private val downloadMutex = Mutex()
    private val refreshMutexes = GeoDataAsset.entries.associateWith { Mutex() }
    private val cacheGenerations = GeoDataAsset.entries.associateWith { 0L }.toMutableMap()
    private val _downloadProgress = MutableStateFlow<Map<GeoDataAsset, GeoDataDownloadProgress>>(emptyMap())
    private val _lastUpdated = MutableStateFlow<Map<GeoDataAsset, Long?>>(emptyMap())
    private val _cachedSizes = MutableStateFlow<Map<GeoDataAsset, Long>>(emptyMap())

    val downloadProgress: StateFlow<Map<GeoDataAsset, GeoDataDownloadProgress>> = _downloadProgress.asStateFlow()
    val lastUpdated: StateFlow<Map<GeoDataAsset, Long?>> = _lastUpdated.asStateFlow()
    val cachedSizes: StateFlow<Map<GeoDataAsset, Long>> = _cachedSizes.asStateFlow()

    suspend fun loadLastUpdated() = withContext(ioDispatcher) {
        downloadMutex.withLock { updateLastUpdated() }
    }

    suspend fun needsRefresh(): Boolean = withContext(ioDispatcher) {
        downloadMutex.withLock { resolveState().needsDownload }
    }

    suspend fun ensureReady(): GeoDataStatus = withContext(ioDispatcher) {
        downloadMutex.withLock {
            binaryDir.mkdirs()
            val state = resolveState()

            if (state.needsDownload) {
                trackDownloads(GeoDataAsset.entries.toSet()) {
                    httpClient.use { client ->
                        coroutineScope {
                            val geoipDownload = async {
                                download(GeoDataAsset.GEOIP, client, state.geoipUrl, state.geoipFile)
                            }
                            val geositeDownload = async {
                                download(GeoDataAsset.GEOSITE, client, state.geositeUrl, state.geositeFile)
                            }
                            geoipDownload.await()
                            geositeDownload.await()
                        }
                    }
                }
                geoipSourceFile.writeText(state.geoipUrl)
                geositeSourceFile.writeText(state.geositeUrl)
                markUpdated(geoipUpdatedAtFile)
                markUpdated(geositeUpdatedAtFile)
            }

            GeoDataStatus(
                geoipUrl = state.geoipUrl,
                geositeUrl = state.geositeUrl,
                downloaded = state.needsDownload,
            )
        }
    }

    suspend fun refresh(asset: GeoDataAsset) = withContext(ioDispatcher) {
        val (state, generation) = downloadMutex.withLock { resolveState(seedBundled = false) to cacheGenerations.getValue(asset) }
        trackDownloads(setOf(asset)) {
            httpClient.use { client ->
                val isGeoip = asset == GeoDataAsset.GEOIP
                refreshAsset(
                    asset,
                    client,
                    if (isGeoip) state.geoipUrl else state.geositeUrl,
                    if (isGeoip) state.geoipFile else state.geositeFile,
                    if (isGeoip) geoipSourceFile else geositeSourceFile,
                    if (isGeoip) geoipUpdatedAtFile else geositeUpdatedAtFile,
                    generation,
                )
            }
        }
    }

    suspend fun clearCachedData(asset: GeoDataAsset? = null) = withContext(ioDispatcher) {
        downloadMutex.withLock {
            val assets = asset?.let { listOf(it) } ?: GeoDataAsset.entries
            assets.forEach { current ->
                val isGeoip = current == GeoDataAsset.GEOIP
                listOf(
                    File(binaryDir, current.fileName),
                    File(binaryDir, "${current.fileName}.download"),
                    if (isGeoip) geoipSourceFile else geositeSourceFile,
                    if (isGeoip) geoipUpdatedAtFile else geositeUpdatedAtFile,
                ).forEach { file ->
                    if (file.exists() && !file.delete()) {
                        throw IOException("Unable to delete ${file.name}")
                    }
                }
                cacheGenerations[current] = cacheGenerations.getValue(current) + 1
            }
            _downloadProgress.update { it - assets.toSet() }
            updateLastUpdated()
        }
    }

    /** Downloads outside the connection lock, then installs each completed file under the lock. */
    suspend fun refreshForScheduledUpdate() = withContext(ioDispatcher) {
        val (state, generations) = downloadMutex.withLock { resolveState() to cacheGenerations.toMap() }
        if (state.needsDownload) return@withContext
        trackDownloads(GeoDataAsset.entries.toSet()) {
            httpClient.use { client ->
                coroutineScope {
                    val geoip = async {
                        runCatching {
                            refreshAsset(
                                GeoDataAsset.GEOIP,
                                client,
                                state.geoipUrl,
                                state.geoipFile,
                                geoipSourceFile,
                                geoipUpdatedAtFile,
                                generations.getValue(GeoDataAsset.GEOIP),
                            )
                        }
                    }
                    val geosite = async {
                        runCatching {
                            refreshAsset(
                                GeoDataAsset.GEOSITE,
                                client,
                                state.geositeUrl,
                                state.geositeFile,
                                geositeSourceFile,
                                geositeUpdatedAtFile,
                                generations.getValue(GeoDataAsset.GEOSITE),
                            )
                        }
                    }
                    val failures = listOfNotNull(geoip.await().exceptionOrNull(), geosite.await().exceptionOrNull())
                    if (failures.isNotEmpty()) throw failures.first()
                }
            }
        }
    }

    private suspend fun refreshAsset(
        asset: GeoDataAsset,
        client: OkHttpClient,
        url: String,
        targetFile: File,
        sourceMarkerFile: File,
        updatedAtFile: File,
        generation: Long,
    ) = refreshMutexes.getValue(asset).withLock {
        val stagedFile = File.createTempFile("${targetFile.name}-refresh-", ".dat", binaryDir)
        try {
            download(asset, client, url, stagedFile)
            downloadMutex.withLock install@{
                if (generation != cacheGenerations.getValue(asset)) return@install
                val current = resolveState(seedBundled = false)
                val currentUrl = when (asset) {
                    GeoDataAsset.GEOIP -> current.geoipUrl
                    GeoDataAsset.GEOSITE -> current.geositeUrl
                }
                if (currentUrl != url) return@install
                if (!stagedFile.renameTo(targetFile)) {
                    throw IOException("Unable to install refreshed ${targetFile.name}")
                }
                sourceMarkerFile.writeText(url)
                markUpdated(updatedAtFile)
            }
        } finally {
            stagedFile.delete()
        }
    }

    private fun markUpdated(updatedAtFile: File) {
        updatedAtFile.writeText(System.currentTimeMillis().toString())
        updateLastUpdated()
    }

    private fun updateLastUpdated() {
        _lastUpdated.value = mapOf(
            GeoDataAsset.GEOIP to geoDataUpdatedAt(File(binaryDir, GEOIP_FILE_NAME), geoipUpdatedAtFile),
            GeoDataAsset.GEOSITE to geoDataUpdatedAt(File(binaryDir, GEOSITE_FILE_NAME), geositeUpdatedAtFile),
        )
        _cachedSizes.value = GeoDataAsset.entries.associateWith { File(binaryDir, it.fileName).length() }
    }

    private fun download(asset: GeoDataAsset, client: OkHttpClient, sourceUrl: String, targetFile: File) {
        val normalizedUrl = normalizeGeoDataUrl(sourceUrl)
        normalizedUrl.toHttpUrlOrNull() ?: throw IOException("Invalid geo data URL: $normalizedUrl")
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.download")
        val request = Request.Builder().url(normalizedUrl).build()
        var completed = false
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Failed to download ${targetFile.name}: HTTP ${response.code}")
                }
                writeResponseBody(asset, response.body, tempFile)
            }

            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            completed = true
        } finally {
            if (!completed) tempFile.delete()
        }
    }

    private fun writeResponseBody(asset: GeoDataAsset, responseBody: ResponseBody, tempFile: File) {
        val totalBytes = responseBody.contentLength().takeIf { it >= 0L }
        updateProgress(asset, bytesDownloaded = 0L, totalBytes = totalBytes)
        responseBody.byteStream().use { input ->
            tempFile.outputStream().use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE_BYTES)
                var bytesDownloaded = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    bytesDownloaded += read
                    updateProgress(asset, bytesDownloaded, totalBytes)
                }
            }
        }
    }

    private suspend fun <T> trackDownloads(assets: Set<GeoDataAsset>, block: suspend () -> T): T {
        _downloadProgress.update { current ->
            current + assets.associateWith { GeoDataDownloadProgress(bytesDownloaded = 0L, totalBytes = null) }
        }
        return try {
            block()
        } finally {
            _downloadProgress.update { current -> current - assets }
        }
    }

    private fun updateProgress(asset: GeoDataAsset, bytesDownloaded: Long, totalBytes: Long?) {
        _downloadProgress.update { current ->
            current + (asset to GeoDataDownloadProgress(bytesDownloaded, totalBytes))
        }
    }

    private fun File.readTextOrNull(): String? = takeIf(File::exists)?.readText()?.trim()

    private suspend fun resolveState(seedBundled: Boolean = true): ResolvedGeoDataState {
        binaryDir.mkdirs()

        val configuredGeoipUrl = normalizeGeoDataUrl(urlSettings.geoipUrl.first())
        val configuredGeositeUrl = normalizeGeoDataUrl(urlSettings.geositeUrl.first())
        val geoipUrl = configuredGeoipUrl.ifEmpty { GeoDataDefaults.GEOIP_URL }
        val geositeUrl = configuredGeositeUrl.ifEmpty { GeoDataDefaults.GEOSITE_URL }
        val geoipFile = File(binaryDir, GEOIP_FILE_NAME)
        val geositeFile = File(binaryDir, GEOSITE_FILE_NAME)
        if (seedBundled) {
            seedBundledGeoData(
                geoipUrl,
                GeoDataDefaults.GEOIP_URL,
                geoipFile,
                geoipSourceFile,
            ) { context.assets.open(GEOIP_FILE_NAME) }
            seedBundledGeoData(
                geositeUrl,
                GeoDataDefaults.GEOSITE_URL,
                geositeFile,
                geositeSourceFile,
            ) { context.assets.open(GEOSITE_FILE_NAME) }
        }
        updateLastUpdated()
        val needsDownload = geoipSourceFile.readTextOrNull() != geoipUrl ||
            geositeSourceFile.readTextOrNull() != geositeUrl ||
            !geoipFile.exists() ||
            !geositeFile.exists()

        return ResolvedGeoDataState(
            geoipUrl = geoipUrl,
            geositeUrl = geositeUrl,
            geoipFile = geoipFile,
            geositeFile = geositeFile,
            needsDownload = needsDownload,
        )
    }

    private data class ResolvedGeoDataState(
        val geoipUrl: String,
        val geositeUrl: String,
        val geoipFile: File,
        val geositeFile: File,
        val needsDownload: Boolean,
    )

    private companion object {
        const val DOWNLOAD_BUFFER_SIZE_BYTES = 64 * 1024
    }
}
