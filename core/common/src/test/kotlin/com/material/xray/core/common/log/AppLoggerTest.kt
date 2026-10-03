package com.material.xray.core.common.log

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLoggerTest {
    private data class Line(val level: AppLogger.Level, val tag: String, val message: String, val throwable: Throwable?)

    @Test
    fun `level shortcuts forward tag, message and throwable`() {
        val lines = mutableListOf<Line>()
        val logger = AppLogger { level, tag, message, throwable -> lines += Line(level, tag, message, throwable) }
        val error = IllegalStateException("boom")

        logger.d("A", "debug")
        logger.i("B", "info")
        logger.w("C", "warn", error)
        logger.e("D", "error", error)

        assertEquals(
            listOf(
                Line(AppLogger.Level.DEBUG, "A", "debug", null),
                Line(AppLogger.Level.INFO, "B", "info", null),
                Line(AppLogger.Level.WARN, "C", "warn", error),
                Line(AppLogger.Level.ERROR, "D", "error", error),
            ),
            lines,
        )
    }

    @Test
    fun `print logger writes logcat-style lines and stack traces`() {
        val bytes = ByteArrayOutputStream()
        val logger = PrintAppLogger(PrintStream(bytes, true, Charsets.UTF_8.name()))

        logger.i("Shell", "opened")
        logger.w("Stats", "query failed", IllegalStateException("socket closed"))

        val output = bytes.toString(Charsets.UTF_8.name()).lines()
        assertEquals("I/Shell: opened", output[0])
        assertEquals("W/Stats: query failed", output[1])
        assertTrue(output[2].startsWith("java.lang.IllegalStateException: socket closed"))
        assertTrue(output[3].trimStart().startsWith("at "))
    }

    @Test
    fun `no-op logger accepts every level`() {
        NoOpAppLogger.d("T", "m")
        NoOpAppLogger.e("T", "m", RuntimeException())
    }
}
