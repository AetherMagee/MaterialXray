package com.material.xray.ui.logs

import com.material.xray.core.common.log.LogEntry
import com.material.xray.core.common.log.LogSource
import org.junit.Assert.assertEquals
import org.junit.Test

class LogSeverityTest {
    @Test
    fun `xray severity follows bracketed level rather than message text`() {
        assertEquals(LogSeverity.ERROR, xray("2026/09/27 12:00:00 [Error] connection failed").severity())
        assertEquals(LogSeverity.WARNING, xray("2026/09/27 12:00:01 [Warning] error retrying").severity())
        assertEquals(LogSeverity.NORMAL, xray("2026/09/27 12:00:02 [Info] error count: 0").severity())
    }

    @Test
    fun `app errors retain text matching`() {
        assertEquals(LogSeverity.ERROR, app("ERROR: Could not start Xray").severity())
        assertEquals(LogSeverity.ERROR, app("Connection failed").severity())
        assertEquals(LogSeverity.NORMAL, app("Xray settings: logLevel=error").severity())
        assertEquals(LogSeverity.NORMAL, app("Connection established").severity())
    }

    private fun xray(message: String) = LogEntry(id = 0, source = LogSource.XRAY, message = message)

    private fun app(message: String) = LogEntry(id = 0, source = LogSource.APP, message = message)
}
