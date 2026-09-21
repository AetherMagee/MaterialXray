package com.material.xray.core.xray

import android.content.Context
import com.material.xray.core.network.AppHttpClient
import com.material.xray.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
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

internal const val GEOIP_FILE_NAME = "geoip.dat"
internal const val GEOSITE_FILE_NAME = "geosite.dat"

internal fun normalizeGeoDataUrl(url: String): String = url.trim()

data class GeoDataDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
) {
    val fraction: Float?
        get() = totalBytes
            ?.takeIf { it > 0L }
            ?.let { total -> (bytesDownloaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat() }
}

internal fun combinedGeoDataDownloadFraction(progress: Collection<GeoDataDownloadProgress>): Float? {
    if (progress.isEmpty() || progress.any { it.totalBytes == null || it.totalBytes <= 0L }) return null
    val totalBytes = progress.sumOf { requireNotNull(it.totalBytes) }
    if (totalBytes <= 0L) return null
    return (progress.sumOf(GeoDataDownloadProgress::bytesDownloaded).toDouble() / totalBytes)
        .coerceIn(0.0, 1.0)
        .toFloat()
}

data class GeoDataStatus(
    val geoipUrl: String,
    val geositeUrl: String,
    val downloaded: Boolean,
)

enum class GeoDataAsset {
    GEOIP,
    GEOSITE,
}

@Singleton
class GeoDataManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: AppHttpClient,
    private val settingsRepository: SettingsRepository,
) {
    private val binaryDir get() = File(context.filesDir, "bin")
    private val geoipSourceFile get() = File(binaryDir, "geoip-source")
    private val geositeSourceFile get() = File(binaryDir, "geosite-source")
    private val geoipUpdatedAtFile get() = File(binaryDir, "geoip-updated-at")
    private val geositeUpdatedAtFile get() = File(binaryDir, "geosite-updated-at")
    private val downloadMutex = Mutex()
    private val _downloadProgress = MutableStateFlow<Map<GeoDataAsset, GeoDataDownloadProgress>>(emptyMap())

    val downloadProgress: StateFlow<Map<GeoDataAsset, GeoDataDownloadProgress>> = _downloadProgress.asStateFlow()

    suspend fun needsRefresh(): Boolean = withContext(Dispatchers.IO) {
        resolveState().needsDownload
    }

    suspend fun ensureReady(): GeoDataStatus = withContext(Dispatchers.IO) {
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

    suspend fun refresh(asset: GeoDataAsset) = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            binaryDir.mkdirs()
            val state = resolveState()
            trackDownloads(setOf(asset)) {
                httpClient.use { client ->
                    when (asset) {
                        GeoDataAsset.GEOIP -> {
                            download(asset, client, state.geoipUrl, state.geoipFile)
                            geoipSourceFile.writeText(state.geoipUrl)
                            markUpdated(geoipUpdatedAtFile)
                        }
                        GeoDataAsset.GEOSITE -> {
                            download(asset, client, state.geositeUrl, state.geositeFile)
                            geositeSourceFile.writeText(state.geositeUrl)
                            markUpdated(geositeUpdatedAtFile)
                        }
                    }
                }
            }
        }
    }

    /** Refreshes initialized files without preempting connection-driven initial downloads. */
    suspend fun refreshForScheduledUpdate() = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            val state = resolveState()
            if (state.needsDownload) return@withLock
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
                                )
                            }
                        }
                        val failures = listOfNotNull(geoip.await().exceptionOrNull(), geosite.await().exceptionOrNull())
                        if (failures.isNotEmpty()) throw failures.first()
                    }
                }
            }
        }
    }

    private fun refreshAsset(
        asset: GeoDataAsset,
        client: OkHttpClient,
        url: String,
        targetFile: File,
        sourceMarkerFile: File,
        updatedAtFile: File,
    ) {
        download(asset, client, url, targetFile)
        sourceMarkerFile.writeText(url)
        markUpdated(updatedAtFile)
    }

    private fun markUpdated(updatedAtFile: File) {
        updatedAtFile.writeText(System.currentTimeMillis().toString())
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

    private suspend fun resolveState(): ResolvedGeoDataState {
        binaryDir.mkdirs()

        val configuredGeoipUrl = normalizeGeoDataUrl(settingsRepository.geoipUrl.first())
        val configuredGeositeUrl = normalizeGeoDataUrl(settingsRepository.geositeUrl.first())
        val geoipUrl = configuredGeoipUrl.ifEmpty { SettingsRepository.DEFAULT_GEOIP_URL }
        val geositeUrl = configuredGeositeUrl.ifEmpty { SettingsRepository.DEFAULT_GEOSITE_URL }
        val geoipFile = File(binaryDir, GEOIP_FILE_NAME)
        val geositeFile = File(binaryDir, GEOSITE_FILE_NAME)
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
