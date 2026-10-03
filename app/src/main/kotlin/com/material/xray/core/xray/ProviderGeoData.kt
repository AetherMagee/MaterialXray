package com.material.xray.core.xray

import android.content.Context
import com.material.xray.core.common.connection.ConnectionStateCoordinator
import com.material.xray.core.common.di.ApplicationScope
import com.material.xray.core.common.log.LogBuffer
import com.material.xray.core.common.log.LogSource
import com.material.xray.core.network.AppHttpClient
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.toSubscriptionRouting
import com.material.xray.model.ConnectionState
import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleOperator
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
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

internal val PROVIDER_GEO_DATA_STALE_AFTER_MS = TimeUnit.HOURS.toMillis(48)
internal val PROVIDER_GEO_DATA_RETRY_AFTER_MS = TimeUnit.HOURS.toMillis(4)
private const val PROVIDER_GEO_DATA_FILE_PREFIX = "provider-"
private const val PROVIDER_GEO_DATA_FILE_SUFFIX = ".dat"
private const val PROVIDER_GEO_DATA_HASH_LENGTH = 16
private const val DOWNLOAD_SUFFIX = ".download"
private const val GEOSITE_PREFIX = "geosite:"
private const val GEOIP_PREFIX = "geoip:"

/**
 * Name of the file holding the provider geodata downloaded from [url]. Files are keyed by URL, so
 * subscriptions that point at the same data share one download.
 */
internal fun providerGeoDataFileName(url: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
    val hash = digest.joinToString("") { "%02x".format(Locale.ROOT, it) }.take(PROVIDER_GEO_DATA_HASH_LENGTH)
    return "$PROVIDER_GEO_DATA_FILE_PREFIX$hash$PROVIDER_GEO_DATA_FILE_SUFFIX"
}

private fun isProviderGeoDataFileName(name: String): Boolean = name.startsWith(PROVIDER_GEO_DATA_FILE_PREFIX) && name.endsWith(PROVIDER_GEO_DATA_FILE_SUFFIX)

internal data class ProviderGeoDataFailure(val at: Long, val throughTunnel: Boolean)

/**
 * Whether a provider file should be fetched now, given what is on disk and how the last attempt
 * failed. A failure outside the tunnel says nothing about the tunnel, so it never delays an attempt
 * through it.
 */
internal fun providerGeoDataNeedsDownload(
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
internal fun readGeoDataCodes(input: InputStream): Set<String> {
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

internal fun readGeoDataCodes(file: File): Set<String> = file.inputStream().buffered().use(::readGeoDataCodes)

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

internal data class ProviderGeoDataResolution(
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
internal fun resolveProviderGeoData(
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
internal fun providerGeoDataNotice(
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

internal fun List<RoutingRule>.providerGeoDataUrls(): Set<String> = flatMapTo(mutableSetOf()) { it.geoData?.urls().orEmpty() }

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
                    val name = file.name.removeSuffix(DOWNLOAD_SUFFIX)
                    isProviderGeoDataFileName(name) && (name !in keep || file.name.endsWith(DOWNLOAD_SUFFIX))
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
        val temporary = File(binaryDir, "${target.name}$DOWNLOAD_SUFFIX")
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
