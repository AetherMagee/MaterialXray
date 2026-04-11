package com.materialxray.service

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val source: LogSource,
    val message: String,
)

enum class LogSource { APP, XRAY }

@Singleton
class LogBuffer @Inject constructor() {
    private val maxSize = 2000
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    @Synchronized
    fun append(source: LogSource, message: String) {
        // Mirror to logcat
        when (source) {
            LogSource.APP -> Log.d("MXray", message)
            LogSource.XRAY -> Log.d("MXray.xray", message)
        }

        val current = _entries.value.toMutableList()
        current.add(LogEntry(source = source, message = message))
        if (current.size > maxSize) {
            _entries.value = current.drop(current.size - maxSize)
        } else {
            _entries.value = current
        }
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }

    fun formatAll(): String = _entries.value.joinToString("\n") { entry ->
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(entry.timestamp))
        "$time [${entry.source.name}] ${entry.message}"
    }
}
