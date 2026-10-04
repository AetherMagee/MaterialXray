package com.material.xray.core.xray

import com.material.xray.core.model.RoutingRule
import com.material.xray.core.model.RoutingRuleOperator
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The bundled geodata files Xray loads from its working directory. */
const val GEOIP_FILE_NAME = "geoip.dat"
const val GEOSITE_FILE_NAME = "geosite.dat"

internal val PROVIDER_GEO_DATA_STALE_AFTER_MS = TimeUnit.HOURS.toMillis(48)
internal val PROVIDER_GEO_DATA_RETRY_AFTER_MS = TimeUnit.HOURS.toMillis(4)
private const val PROVIDER_GEO_DATA_FILE_PREFIX = "provider-"
const val PROVIDER_GEO_DATA_FILE_SUFFIX = ".dat"
private const val PROVIDER_GEO_DATA_HASH_LENGTH = 16
const val PROVIDER_GEO_DATA_DOWNLOAD_SUFFIX = ".download"
private const val GEOSITE_PREFIX = "geosite:"
private const val GEOIP_PREFIX = "geoip:"

/**
 * Name of the file holding the provider geodata downloaded from [url]. Files are keyed by URL, so
 * subscriptions that point at the same data share one download.
 */
fun providerGeoDataFileName(url: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
    val hash = digest.joinToString("") { "%02x".format(Locale.ROOT, it) }.take(PROVIDER_GEO_DATA_HASH_LENGTH)
    return "$PROVIDER_GEO_DATA_FILE_PREFIX$hash$PROVIDER_GEO_DATA_FILE_SUFFIX"
}

fun isProviderGeoDataFileName(name: String): Boolean = name.startsWith(PROVIDER_GEO_DATA_FILE_PREFIX) && name.endsWith(PROVIDER_GEO_DATA_FILE_SUFFIX)

data class ProviderGeoDataFailure(val at: Long, val throughTunnel: Boolean)

/**
 * Whether a provider file should be fetched now, given what is on disk and how the last attempt
 * failed. A failure outside the tunnel says nothing about the tunnel, so it never delays an attempt
 * through it.
 */
fun providerGeoDataNeedsDownload(
    lastModified: Long?,
    lastFailure: ProviderGeoDataFailure?,
    now: Long,
    force: Boolean,
    throughTunnel: Boolean,
): Boolean {
    val stale = lastModified == null || now - lastModified >= PROVIDER_GEO_DATA_STALE_AFTER_MS
    if (!stale) return false
    if (force || lastFailure == null) return true
    if (throughTunnel && !lastFailure.throughTunnel) return true
    return now - lastFailure.at >= PROVIDER_GEO_DATA_RETRY_AFTER_MS
}

/**
 * Reads the category codes of a `geoip.dat` or `geosite.dat`. Both are a protobuf list whose
 * entries carry their code in field 1, so the rest of every entry is skipped without being parsed.
 * Throws [IOException] for anything that is not such a list, which is how a download is validated.
 */
fun readGeoDataCodes(input: InputStream): Set<String> {
    val reader = ProtobufReader(input)
    val codes = mutableSetOf<String>()
    while (true) {
        val entryTag = reader.readVarintOrNull() ?: break
        if (entryTag != LIST_ENTRY_TAG) throw IOException("Not a geodata file")
        var remaining = reader.readVarint()
        while (remaining > 0) {
            val start = reader.position
            val tag = reader.readVarint()
            val wireType = (tag and WIRE_TYPE_MASK).toInt()
            if (tag == LIST_ENTRY_TAG) {
                codes += reader.readString(reader.readVarint()).uppercase()
            } else {
                reader.skipField(wireType)
            }
            remaining -= reader.position - start
        }
        if (remaining < 0) throw IOException("Corrupted geodata entry")
    }
    return codes
}

fun readGeoDataCodes(file: File): Set<String> = file.inputStream().buffered().use(::readGeoDataCodes)

private const val LIST_ENTRY_TAG = 0x0AL
private const val WIRE_TYPE_MASK = 0x07L
private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH_DELIMITED = 2
private const val WIRE_FIXED32 = 5
private const val FIXED64_BYTES = 8L
private const val FIXED32_BYTES = 4L
private const val VARINT_PAYLOAD_BITS = 7
private const val VARINT_PAYLOAD_MASK = 0x7F
private const val VARINT_CONTINUATION = 0x80
private const val VARINT_MAX_SHIFT = 63
private const val MAX_STRING_BYTES = 1024L

private class ProtobufReader(private val input: InputStream) {
    var position = 0L
        private set

    fun readVarintOrNull(): Long? {
        val first = input.read()
        if (first < 0) return null
        position++
        return continueVarint(first)
    }

    fun readVarint(): Long = continueVarint(readByte())

    // Only short category codes are read whole, so a larger length means the file is not geodata.
    fun readString(length: Long): String {
        if (length !in 0..MAX_STRING_BYTES) throw IOException("Unexpected protobuf string length $length")
        val bytes = ByteArray(length.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
        position += length
        return String(bytes, Charsets.UTF_8)
    }

    fun skipField(wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> readVarint()
            WIRE_FIXED64 -> skip(FIXED64_BYTES)
            WIRE_LENGTH_DELIMITED -> skip(readVarint())
            WIRE_FIXED32 -> skip(FIXED32_BYTES)
            else -> throw IOException("Unsupported protobuf wire type $wireType")
        }
    }

    private fun continueVarint(firstByte: Int): Long {
        var value = 0L
        var shift = 0
        var byte = firstByte
        while (true) {
            value = value or ((byte and VARINT_PAYLOAD_MASK).toLong() shl shift)
            if (byte and VARINT_CONTINUATION == 0) return value
            shift += VARINT_PAYLOAD_BITS
            if (shift > VARINT_MAX_SHIFT) throw IOException("Malformed varint")
            byte = readByte()
        }
    }

    private fun readByte(): Int {
        val byte = input.read()
        if (byte < 0) throw EOFException()
        position++
        return byte
    }

    private fun skip(count: Long) {
        if (count < 0) throw IOException("Negative protobuf length")
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) throw EOFException()
                remaining--
            }
        }
        position += count
    }
}

data class ProviderGeoDataResolution(
    val rules: List<RoutingRule>,
    /** Provider files the resolved rules now read from. */
    val usedUrls: Set<String> = emptySet(),
    /** Provider files the rules point at that are not on disk, which puts them in compatibility mode. */
    val unavailableUrls: Set<String> = emptySet(),
    /** Entries no available file defines; Xray refuses to start on any of them, so they are left out. */
    val droppedEntries: List<String> = emptyList(),
)

/**
 * Points the `geoip:` and `geosite:` entries of provider rules at the provider's own files.
 *
 * An entry the provider file defines is rewritten to `ext:<file>:<code>`. Without that file (or
 * when it lacks the code) the entry falls back to the app's default data, and when that lacks the
 * code too the entry is dropped. A rule that loses a whole condition is dropped with it rather than
 * left to match more traffic than the provider meant.
 *
 * [providerCodes] returns the codes of the provider file downloaded from a URL, or null when that
 * file is not available.
 */
fun resolveProviderGeoData(
    rules: List<RoutingRule>,
    defaultGeoipCodes: Set<String>,
    defaultGeositeCodes: Set<String>,
    providerCodes: (String) -> Set<String>?,
): ProviderGeoDataResolution {
    val usedUrls = mutableSetOf<String>()
    val unavailableUrls = mutableSetOf<String>()
    val droppedEntries = mutableListOf<String>()

    fun resolveEntry(entry: String, prefix: String, url: String?, defaultCodes: Set<String>): String? {
        val negation = entry.takeWhile { it == '!' }
        val body = entry.substring(negation.length)
        if (!body.startsWith(prefix, ignoreCase = true)) return entry
        val value = body.substring(prefix.length)
        val code = value.trimStart('!').substringBefore('@').uppercase()
        val fileCodes = url?.let(providerCodes)
        if (url != null && fileCodes == null) unavailableUrls += url
        return when {
            url != null && fileCodes != null && code in fileCodes -> {
                usedUrls += url
                "${negation}ext:${providerGeoDataFileName(url)}:$value"
            }
            code in defaultCodes -> entry
            else -> {
                droppedEntries += entry
                null
            }
        }
    }

    val resolved = rules.mapNotNull { rule ->
        val geoData = rule.geoData ?: return@mapNotNull rule
        val domains = rule.domains.mapNotNull { resolveEntry(it, GEOSITE_PREFIX, geoData.geositeUrl, defaultGeositeCodes) }
        val ips = rule.ips.mapNotNull { resolveEntry(it, GEOIP_PREFIX, geoData.geoipUrl, defaultGeoipCodes) }
        val next = rule.copy(domains = domains, ips = ips)
        val lostCondition = (rule.domains.isNotEmpty() && domains.isEmpty()) || (rule.ips.isNotEmpty() && ips.isEmpty())
        when {
            next.matchesAllTraffic() && !rule.matchesAllTraffic() -> null
            rule.operator == RoutingRuleOperator.AND && lostCondition -> null
            else -> next
        }
    }
    return ProviderGeoDataResolution(resolved, usedUrls, unavailableUrls, droppedEntries)
}

data class ProviderGeoDataState(
    /** Provider files currently on disk, by [providerGeoDataFileName]. */
    val availableFiles: Set<String> = emptySet(),
    /** URLs being downloaded right now. */
    val downloading: Set<String> = emptySet(),
    /** URLs the running connection was started without, so it is in compatibility mode. */
    val activeUnavailableUrls: Set<String> = emptySet(),
)

enum class ProviderGeoDataNotice {
    Downloading,
    CompatibilityMode,
    ReadyToApply,
}

/** What the routing screen tells the user about the geodata behind the selected provider's rules. */
fun providerGeoDataNotice(
    urls: Set<String>,
    state: ProviderGeoDataState,
    connected: Boolean,
): ProviderGeoDataNotice? {
    val missing = urls.filterNot { providerGeoDataFileName(it) in state.availableFiles }
    val activeMissing = state.activeUnavailableUrls.intersect(urls)
    val activeUrlsNowAvailable = activeMissing.isNotEmpty() &&
        activeMissing.all { providerGeoDataFileName(it) in state.availableFiles }
    return when {
        missing.any { it in state.downloading } -> ProviderGeoDataNotice.Downloading
        missing.isNotEmpty() -> ProviderGeoDataNotice.CompatibilityMode
        connected && activeUrlsNowAvailable -> ProviderGeoDataNotice.ReadyToApply
        else -> null
    }
}

fun List<RoutingRule>.providerGeoDataUrls(): Set<String> = flatMapTo(mutableSetOf()) { it.geoData?.urls().orEmpty() }
