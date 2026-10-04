package com.material.xray.core.data.xraycore

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** An upstream Xray release that has a verifiable build for this device. */
data class XrayCoreRelease(
    val tag: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val assetUrl: String,
    val assetSha256: String,
)

/**
 * Lists upstream releases straight from GitHub's API. Mirrors are deliberately not used here: the
 * checksum this returns is what every later download is verified against, and an Xray executable
 * carries no signature of its own that could catch a mirror serving something else.
 */
internal fun fetchXrayCoreReleases(client: OkHttpClient, abi: String): List<XrayCoreRelease> {
    val assetName = xrayReleaseAssetName(abi) ?: return emptyList()
    val request = Request.Builder()
        .url("$XRAY_RELEASES_API_URL?per_page=$RELEASES_PER_PAGE")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", XRAY_CORE_USER_AGENT)
        .build()
    val body = client.newCall(request).execute().use { response ->
        if (response.code == HTTP_FORBIDDEN || response.code == HTTP_TOO_MANY_REQUESTS) {
            throw XrayCoreException(XrayCoreFailure.RateLimited)
        }
        if (!response.isSuccessful) throw IOException("GitHub releases request failed with HTTP ${response.code}")
        response.body.string()
    }
    return parseXrayCoreReleases(body, assetName)
}

internal fun parseXrayCoreReleases(body: String, assetName: String): List<XrayCoreRelease> {
    val releases = try {
        Json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
    } catch (error: IllegalArgumentException) {
        throw IOException("GitHub releases response was not valid", error)
    }
    return releases
        .filterNot { it.boolean("draft") }
        .mapNotNull { release -> parseRelease(release, assetName) }
        .filter { isSupportedXrayVersion(it.tag) }
        .sortedWith { left, right -> compareXrayVersions(right.tag, left.tag) ?: right.publishedAt.compareTo(left.publishedAt) }
}

private fun parseRelease(release: JsonObject, assetName: String): XrayCoreRelease? {
    val tag = release.string("tag_name")?.takeIf(::isValidReleaseTag) ?: return null
    val asset = release["assets"]?.jsonArray
        ?.map { it.jsonObject }
        ?.firstOrNull { it.string("name") == assetName }
        ?: return null
    val url = asset.string("browser_download_url")?.takeIf { isOfficialAssetUrl(it, tag, assetName) } ?: return null
    val sha256 = parseApiDigest(asset.string("digest")) ?: return null
    return XrayCoreRelease(
        tag = tag,
        publishedAt = release.string("published_at").orEmpty(),
        prerelease = release.boolean("prerelease"),
        assetUrl = url,
        assetSha256 = sha256,
    )
}

/** GitHub reports asset digests as `sha256:<hex>`. */
internal fun parseApiDigest(digest: String?): String? {
    val (algorithm, value) = digest?.trim()?.split(':', limit = 2)?.takeIf { it.size == 2 } ?: return null
    if (!algorithm.equals("sha256", ignoreCase = true)) return null
    return value.lowercase().takeIf(SHA256_PATTERN::matches)
}

/** The upstream archive name for [abi], or null when upstream publishes no Android build for it. */
fun xrayReleaseAssetName(abi: String): String? = when (abi) {
    "arm64-v8a" -> "Xray-android-arm64-v8a.zip"
    "x86_64" -> "Xray-android-amd64.zip"
    else -> null
}

/** Whether [version] is at least [MINIMUM_XRAY_VERSION]. Unparseable versions are not. */
fun isSupportedXrayVersion(version: String): Boolean = (compareXrayVersions(version, MINIMUM_XRAY_VERSION) ?: -1) >= 0

/** Compares the numeric part of two Xray versions, or returns null when either is not numeric. */
fun compareXrayVersions(left: String, right: String): Int? {
    val leftParts = numericVersion(left) ?: return null
    val rightParts = numericVersion(right) ?: return null
    for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
        val comparison = leftParts.getOrElse(index) { 0 }.compareTo(rightParts.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    return 0
}

/** Drops the `v` of a tag such as `v26.9.30`, which `xray version` does not print. */
fun normalizeXrayVersion(version: String): String = version.trim().removePrefix("v")

private fun numericVersion(version: String): List<Int>? {
    val parts = normalizeXrayVersion(version).substringBefore('-').substringBefore('+').split('.')
    if (parts.size < 2) return null
    return parts.map { it.toIntOrNull()?.takeIf { number -> number >= 0 } ?: return null }
}

private fun isValidReleaseTag(tag: String): Boolean = RELEASE_TAG_PATTERN.matches(tag)

private fun isOfficialAssetUrl(value: String, tag: String, assetName: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    return url.isHttps &&
        url.host == "github.com" &&
        url.encodedPath == "/$XRAY_REPOSITORY/releases/download/$tag/$assetName"
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.boolean(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull == true

/** The oldest release whose Android build adopts a TUN fd and understands every config the app generates. */
const val MINIMUM_XRAY_VERSION = "v26.1.23"

internal const val XRAY_CORE_USER_AGENT = "MaterialXray"
private const val XRAY_REPOSITORY = "XTLS/Xray-core"
private const val XRAY_RELEASES_API_URL = "https://api.github.com/repos/$XRAY_REPOSITORY/releases"
private const val RELEASES_PER_PAGE = 100
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val RELEASE_TAG_PATTERN = Regex("v?[0-9]+(\\.[0-9]+)+(-[A-Za-z0-9.]+)?")
