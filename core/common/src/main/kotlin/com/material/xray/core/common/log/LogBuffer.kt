package com.material.xray.core.common.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.annotation.Singleton

data class LogEntry(
    val id: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val source: LogSource,
    val message: String,
)

enum class LogSource { APP, XRAY }

val xrayTimestampPrefix = Regex(
    "^\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\s+",
)

val LogEntry.displayMessage: String
    get() = if (source == LogSource.XRAY) message.replaceFirst(xrayTimestampPrefix, "") else message

/**
 * Mirrors every entry [LogBuffer] records to a platform log, such as logcat on Android, so the
 * entries can also be read from outside the app.
 */
fun interface LogEcho {
    fun echo(source: LogSource, message: String)

    companion object {
        /** Echoes nothing. */
        val None = LogEcho { _, _ -> }
    }
}

@Singleton
class LogBuffer(private val echo: LogEcho) {
    /** A buffer that echoes nowhere, for tests and hosts without a platform log. */
    constructor() : this(LogEcho.None)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries
    private val buffer = ArrayDeque<LogEntry>(MAX_SIZE)
    private var nextId = 0L
    private var appEntryCount = 0

    fun append(source: LogSource, message: String) {
        appendAll(source, listOf(message))
    }

    fun appendAll(source: LogSource, messages: List<String>) {
        if (messages.isEmpty()) return
        messages.forEach { message -> runCatching { echo.echo(source, message) } }

        synchronized(this) {
            messages.forEach { message ->
                if (buffer.size == MAX_SIZE) {
                    val evictionIndex = if (source == LogSource.XRAY && appEntryCount <= MIN_RETAINED_APP_ENTRIES) {
                        buffer.indexOfFirst { entry -> entry.source == LogSource.XRAY }.takeIf { it >= 0 } ?: 0
                    } else {
                        0
                    }
                    if (buffer.removeAt(evictionIndex).source == LogSource.APP) appEntryCount--
                }
                buffer.addLast(
                    LogEntry(
                        id = nextId++,
                        source = source,
                        message = message,
                    ),
                )
                if (source == LogSource.APP) appEntryCount++
            }
            _entries.value = buffer.toList()
        }
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        appEntryCount = 0
        _entries.value = emptyList()
    }

    @Synchronized
    fun clear(source: LogSource) {
        val retained = buffer.filterNot { it.source == source }
        buffer.clear()
        buffer.addAll(retained)
        appEntryCount = retained.count { it.source == LogSource.APP }
        _entries.value = buffer.toList()
    }

    fun formatAll(): String = _entries.value.joinToString("\n") { entry ->
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(entry.timestamp))
        "$time [${entry.source.name}] ${entry.displayMessage}"
    }

    companion object {
        private const val MAX_SIZE = 2000
        private const val MIN_RETAINED_APP_ENTRIES = 256
        const val XRAY_TAIL_SIZE = MAX_SIZE - MIN_RETAINED_APP_ENTRIES
    }
}
