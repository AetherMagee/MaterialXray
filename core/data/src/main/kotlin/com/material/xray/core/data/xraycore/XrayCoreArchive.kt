package com.material.xray.core.data.xraycore

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Why installing or listing cores failed, for the UI to explain. */
enum class XrayCoreFailure {
    /** GitHub could not be reached, or answered with an error. */
    Network,

    /** GitHub's anonymous API limit is used up for this IP; it resets within the hour. */
    RateLimited,

    /** The download did not match the checksum GitHub published for it. */
    Integrity,

    /** The file is not an Xray executable for this device, or a release zip containing one. */
    Unsupported,

    /** The executable did not report a usable version. */
    Broken,

    /** The core is older than the app's generated configs need. */
    TooOld,
}

class XrayCoreException(val failure: XrayCoreFailure, cause: Throwable? = null) : IOException(failure.name, cause)

/**
 * Streams [urls] in order into [destination] until one download matches [expectedSha256]. Every
 * URL serves the same official asset, so a mismatch from one is no reason to trust the next less.
 */
internal suspend fun downloadVerified(
    client: OkHttpClient,
    urls: List<String>,
    expectedSha256: String,
    destination: File,
    onProgress: (downloaded: Long, total: Long?) -> Unit,
) {
    var lastFailure: IOException? = null
    for (url in urls) {
        currentCoroutineContext().ensureActive()
        try {
            val sha256 = download(client, url, destination, onProgress)
            if (sha256 == expectedSha256) return
            lastFailure = XrayCoreException(XrayCoreFailure.Integrity)
        } catch (error: IOException) {
            lastFailure = error
        }
        destination.delete()
    }
    throw lastFailure as? XrayCoreException ?: XrayCoreException(XrayCoreFailure.Network, lastFailure)
}

private suspend fun download(
    client: OkHttpClient,
    url: String,
    destination: File,
    onProgress: (downloaded: Long, total: Long?) -> Unit,
): String {
    val request = Request.Builder()
        .url(url)
        .header("Accept", "application/octet-stream")
        .header("User-Agent", XRAY_CORE_USER_AGENT)
        .build()
    return client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Download failed with HTTP ${response.code}")
        val total = response.body.contentLength().takeIf { it > 0 }
        copyHashing(response.body.byteStream(), destination, MAX_ARCHIVE_BYTES) { onProgress(it, total) }
    }
}

/** Copies [input] into [destination], at most [limit] bytes, and returns the SHA-256 of what it copied. */
internal suspend fun copyHashing(
    input: InputStream,
    destination: File,
    limit: Long,
    onProgress: (Long) -> Unit = {},
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    var copied = 0L
    DigestInputStream(input, digest).use { source ->
        destination.outputStream().use { output ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read < 0) break
                copied += read
                if (copied > limit) throw XrayCoreException(XrayCoreFailure.Unsupported)
                output.write(buffer, 0, read)
                onProgress(copied)
            }
        }
    }
    return digest.digest().toHex()
}

/** Whether [file] starts like a zip archive rather than an executable. */
internal fun isZip(file: File): Boolean = file.inputStream().use { input ->
    val header = ByteArray(ZIP_MAGIC.size)
    input.read(header) == header.size && header.contentEquals(ZIP_MAGIC)
}

/**
 * Extracts the release zip's top-level `xray` executable into [destination] and returns its
 * SHA-256. Nothing else in the archive is needed: the app ships its own geodata.
 */
internal suspend fun extractXrayExecutable(archive: File, destination: File): String {
    ZipInputStream(archive.inputStream().buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory && entry.name == XRAY_ARCHIVE_ENTRY) {
                return copyHashing(zip, destination, MAX_EXECUTABLE_BYTES)
            }
        }
    }
    throw XrayCoreException(XrayCoreFailure.Unsupported)
}

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0') }

private const val XRAY_ARCHIVE_ENTRY = "xray"
private const val BYTE_MASK = 0xFF
private const val HEX_RADIX = 16
private const val BUFFER_SIZE = 64 * 1024
private const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
private const val MAX_EXECUTABLE_BYTES = 256L * 1024 * 1024
private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
