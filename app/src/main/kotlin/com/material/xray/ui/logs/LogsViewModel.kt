package com.material.xray.ui.logs

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import com.material.xray.R
import com.material.xray.core.locale.localizedString
import com.material.xray.service.LogBuffer
import com.material.xray.service.LogEntry
import com.material.xray.service.displayMessage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel

internal const val LOG_EXPORT_FILE_NAME = "material-xray-logs.txt"
private const val LOG_EXPORT_DIRECTORY = "logs"

@KoinViewModel
class LogsViewModel(
    private val context: Application,
    private val logBuffer: LogBuffer,
) : ViewModel() {
    val entries: StateFlow<List<LogEntry>> = logBuffer.entries

    fun clear() = logBuffer.clear()

    fun copyAll() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val label = context.localizedString(
            R.string.clipboard_label_logs,
            context.localizedString(R.string.app_name),
        )
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, logBuffer.formatAll()))
    }

    fun copyEntries(entries: List<LogEntry>) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val timeFormat = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val text = entries.joinToString("\n") { entry ->
            val time = timeFormat.format(java.util.Date(entry.timestamp))
            "$time [${entry.source.name}] ${entry.displayMessage}"
        }
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText(
                context.localizedString(R.string.clipboard_label_logs, context.localizedString(R.string.app_name)),
                text,
            ),
        )
    }

    suspend fun saveLogs(destination: Uri) = withContext(Dispatchers.IO) {
        val outputStream = context.contentResolver.openOutputStream(destination, "wt")
            ?: throw IOException("Unable to open the selected log file")
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(logBuffer.formatAll())
        }
    }

    suspend fun createShareFile(): Uri = withContext(Dispatchers.IO) {
        val exportDirectory = File(context.cacheDir, LOG_EXPORT_DIRECTORY)
        if (!exportDirectory.isDirectory && !exportDirectory.mkdirs()) {
            throw IOException("Unable to create the log export directory")
        }

        val exportFile = File(exportDirectory, LOG_EXPORT_FILE_NAME)
        exportFile.writeText(logBuffer.formatAll(), Charsets.UTF_8)
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile,
        )
    }
}
