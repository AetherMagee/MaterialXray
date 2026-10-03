package com.material.xray.core.common.log

import org.junit.Assert.assertEquals
import org.junit.Test

class LogBufferTest {
    @Test
    fun `Xray timestamp is omitted from formatted messages`() {
        val buffer = LogBuffer()
        buffer.append(LogSource.XRAY, "2026/09/26 12:34:56.123456 [Info] accepted tcp")
        buffer.append(LogSource.XRAY, "2026/09/26 12:34:57 [Warning] retrying")
        buffer.append(LogSource.APP, "2026/09/26 12:34:58 app message")

        val entries = buffer.entries.value
        assertEquals("[Info] accepted tcp", entries[0].displayMessage)
        assertEquals("[Warning] retrying", entries[1].displayMessage)
        assertEquals("2026/09/26 12:34:58 app message", entries[2].displayMessage)
        val formattedLines = buffer.formatAll().lines()
        assertEquals("2026/09/26 12:34:58 app message", formattedLines.last().substringAfter("[APP] "))
        assertEquals("[Info] accepted tcp", formattedLines.first().substringAfter("[XRAY] "))
    }

    @Test
    fun `batch append publishes ordered entries and retains the configured tail`() {
        val buffer = LogBuffer()

        buffer.appendAll(LogSource.XRAY, (0 until 2_500).map { "line-$it" })

        val entries = buffer.entries.value
        assertEquals(2_000, entries.size)
        assertEquals("line-500", entries.first().message)
        assertEquals("line-2499", entries.last().message)
        assertEquals((500L..2_499L).toList(), entries.map { it.id })
    }

    @Test
    fun `source clear preserves diagnostic entries from other sources`() {
        val buffer = LogBuffer()
        buffer.append(LogSource.APP, "recovery reason")
        buffer.append(LogSource.XRAY, "old core output")

        buffer.clear(LogSource.XRAY)

        assertEquals(listOf("recovery reason"), buffer.entries.value.map { it.message })
    }

    @Test
    fun `Xray debug flood retains application diagnostics`() {
        val buffer = LogBuffer()
        buffer.append(LogSource.APP, "network change detected")

        buffer.appendAll(LogSource.XRAY, (0 until 2_500).map { "debug-$it" })

        val entries = buffer.entries.value
        assertEquals(2_000, entries.size)
        assertEquals("network change detected", entries.first().message)
        assertEquals("debug-2499", entries.last().message)
    }
}
