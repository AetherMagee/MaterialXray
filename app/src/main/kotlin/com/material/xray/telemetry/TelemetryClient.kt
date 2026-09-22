package com.material.xray.telemetry

import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SpanStatus
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.metrics.MetricsUnit
import io.sentry.metrics.SentryMetricsParameters
import io.sentry.protocol.Message
import io.sentry.protocol.User
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface TelemetryClient {
    val isEnabled: Boolean

    fun enable()

    fun disable()

    fun setTag(key: String, value: String)

    fun count(name: String, attributes: Map<String, Any>)

    fun distributionMillis(name: String, value: Long, attributes: Map<String, Any>)

    fun addBreadcrumb(category: String, message: String, data: Map<String, Any>)

    fun startTransaction(name: String, operation: String): TelemetryTransaction

    fun captureException(error: Throwable, fingerprint: List<String>, tags: Map<String, String>)

    fun captureMessage(message: String, fingerprint: String, tags: Map<String, String>)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TelemetryModule {
    @Binds
    @Singleton
    abstract fun bindTelemetryClient(client: SentryTelemetryClient): TelemetryClient
}

interface TelemetryTransaction {
    fun setTag(key: String, value: String)

    fun startChild(operation: String, description: String): TelemetrySpan

    fun finish(status: TelemetryStatus)
}

enum class TelemetryStatus {
    Ok,
    InternalError,
    Aborted,
    Cancelled,
}

@Singleton
class SentryTelemetryClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : TelemetryClient {
    override val isEnabled: Boolean
        get() = Sentry.isEnabled()

    override fun enable() {
        initializeSentryTelemetry(context)
    }

    override fun disable() {
        Sentry.close()
        context.telemetryInstallationIdFile().delete()
    }

    override fun setTag(key: String, value: String) = Sentry.setTag(key, value)

    override fun count(name: String, attributes: Map<String, Any>) {
        Sentry.metrics().count(name, 1.0, "none", SentryMetricsParameters.create(attributes))
    }

    override fun distributionMillis(name: String, value: Long, attributes: Map<String, Any>) {
        Sentry.metrics().distribution(
            name,
            value.toDouble(),
            MetricsUnit.Duration.MILLISECOND,
            SentryMetricsParameters.create(attributes),
        )
    }

    override fun addBreadcrumb(category: String, message: String, data: Map<String, Any>) {
        Sentry.addBreadcrumb(
            Breadcrumb().apply {
                this.category = category
                this.message = message
                level = SentryLevel.INFO
                data.forEach { (key, value) -> setData(key, value) }
            },
        )
    }

    override fun startTransaction(name: String, operation: String): TelemetryTransaction = SentryTransaction(Sentry.startTransaction(name, operation))

    override fun captureException(error: Throwable, fingerprint: List<String>, tags: Map<String, String>) {
        Sentry.captureException(error) { scope ->
            scope.fingerprint = fingerprint
            tags.forEach(scope::setTag)
        }
    }

    override fun captureMessage(message: String, fingerprint: String, tags: Map<String, String>) {
        val event = SentryEvent().apply {
            level = SentryLevel.ERROR
            logger = SAFE_EVENT_LOGGER
            this.message = Message().apply { formatted = message }
            fingerprints = listOf(fingerprint)
            tags.forEach { (key, value) -> setTag(key, value) }
        }
        Sentry.captureEvent(event)
    }
}

internal fun initializeSentryTelemetry(context: Context) {
    if (!Sentry.isEnabled()) {
        SentryAndroid.init(context) { options -> configureTelemetryOptions(options, context.isDebuggable()) }
    }
    Sentry.setUser(User().apply { id = context.telemetryInstallationId() })
}

private fun Context.telemetryInstallationId(): String {
    val file = telemetryInstallationIdFile()
    val existing = runCatching { UUID.fromString(file.readText().trim()).toString() }.getOrNull()
    if (existing != null) return existing
    return UUID.randomUUID().toString().also { id ->
        runCatching { file.writeText(id) }
    }
}

private fun Context.telemetryInstallationIdFile(): File = noBackupFilesDir.resolve(INSTALLATION_ID_FILE)

private fun Context.isDebuggable(): Boolean = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

private class SentryTransaction(
    private val transaction: io.sentry.ITransaction,
) : TelemetryTransaction {
    override fun setTag(key: String, value: String) = transaction.setTag(key, value)

    override fun startChild(operation: String, description: String): TelemetrySpan {
        val span = transaction.startChild(operation, description)
        return TelemetrySpan { succeeded ->
            span.finish(if (succeeded) SpanStatus.OK else SpanStatus.INTERNAL_ERROR)
        }
    }

    override fun finish(status: TelemetryStatus) {
        transaction.finish(status.toSentryStatus())
    }
}

internal fun configureTelemetryOptions(options: SentryAndroidOptions, isDebuggable: Boolean) {
    options.dsn = SENTRY_DSN
    options.environment = if (isDebuggable) "development" else "production"
    options.isSendDefaultPii = false
    // Breadcrumbs are only ever added by this package from a fixed vocabulary; every automatic
    // source (Logcat, network, UI, lifecycle) stays disabled below.
    options.maxBreadcrumbs = MAX_BREADCRUMBS
    // Message events carry their own tags; the calling thread's stack is noise.
    options.isAttachStacktrace = false
    options.tracesSampleRate = if (isDebuggable) DEBUG_TRACE_SAMPLE_RATE else RELEASE_TRACE_SAMPLE_RATE
    options.profilesSampleRate = 0.0
    options.profileSessionSampleRate = 0.0
    options.isEnableUserInteractionTracing = false
    options.isEnableUserInteractionBreadcrumbs = false
    options.isEnableAutoActivityLifecycleTracing = false
    options.isEnableStandaloneAppStartTracing = false
    options.enableAllAutoBreadcrumbs(false)
    options.isAttachScreenshot = false
    options.isAttachViewHierarchy = false
    options.isAttachRawTombstone = false
    options.isCollectAdditionalContext = false
    options.isCollectExternalStorageContext = false
    options.isEnableRootCheck = false
    options.isSendModules = false
    options.isAnrEnabled = true
    options.isEnableNdk = true
    options.logs.isEnabled = false
    options.metrics.isEnabled = true
    options.beforeSend = { event, _ -> sanitizeTelemetryEvent(event) }
}

internal fun sanitizeTelemetryEvent(event: SentryEvent): SentryEvent {
    event.request = null
    event.serverName = null
    event.exceptions?.forEach { exception -> exception.value = exception.type }
    if (event.logger != SAFE_EVENT_LOGGER) event.message = null
    event.user = event.user?.id?.let { id -> User().apply { this.id = id } }
    return event
}

private fun TelemetryStatus.toSentryStatus(): SpanStatus = when (this) {
    TelemetryStatus.Ok -> SpanStatus.OK
    TelemetryStatus.InternalError -> SpanStatus.INTERNAL_ERROR
    TelemetryStatus.Aborted -> SpanStatus.ABORTED
    TelemetryStatus.Cancelled -> SpanStatus.CANCELLED
}

internal const val SAFE_EVENT_LOGGER = "materialxray.telemetry"
private const val SENTRY_DSN =
    "https://d061f2516e2af78d352e5b5eed19108e@o4512086151397376.ingest.de.sentry.io/4512086156509264"
private const val INSTALLATION_ID_FILE = "diagnostics-installation-id"
private const val MAX_BREADCRUMBS = 60
private const val DEBUG_TRACE_SAMPLE_RATE = 1.0
private const val RELEASE_TRACE_SAMPLE_RATE = 0.1
