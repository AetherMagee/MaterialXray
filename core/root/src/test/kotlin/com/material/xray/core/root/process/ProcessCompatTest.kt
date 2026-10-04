package com.material.xray.core.root.process

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessCompatTest {
    @Test
    fun `legacy liveness detects running and exited processes`() {
        val process = ProcessBuilder("sh", "-c", "sleep 1").start()
        try {
            assertTrue(process.isAliveLegacy())
            process.destroy()
            process.waitFor()
            assertFalse(process.isAliveLegacy())
        } finally {
            process.destroy()
        }
    }

    @Test
    fun `legacy timed wait reports timeout`() {
        val process = ProcessBuilder("sh", "-c", "sleep 1").start()
        try {
            assertFalse(process.waitForLegacy(1, TimeUnit.MILLISECONDS))
        } finally {
            process.destroy()
            process.waitFor()
        }
    }

    @Test
    fun `legacy timed wait reports process exit`() {
        val process = ProcessBuilder("sh", "-c", "exit 0").start()

        assertTrue(process.waitForLegacy(1, TimeUnit.SECONDS))
    }

    @Test
    fun `legacy redirection appends complete process output`() {
        val outputFile = File.createTempFile("process-compat-", ".log")
        try {
            outputFile.writeText("first\n")
            val process = RedirectedProcess.start(
                ProcessBuilder("sh", "-c", "printf 'second\\n'"),
                outputFile,
                append = true,
                modernProcessApi = false,
            )

            assertTrue(process.waitFor(1, TimeUnit.SECONDS))
            assertEquals("first\nsecond\n", outputFile.readText())
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun `modern redirection appends and truncates process output`() {
        val outputFile = File.createTempFile("process-compat-", ".log")
        try {
            outputFile.writeText("first\n")
            val appended = RedirectedProcess.start(
                ProcessBuilder("sh", "-c", "printf 'second\\n'"),
                outputFile,
                append = true,
                modernProcessApi = true,
            )
            assertTrue(appended.waitFor(1, TimeUnit.SECONDS))
            assertEquals("first\nsecond\n", outputFile.readText())

            val truncated = RedirectedProcess.start(
                ProcessBuilder("sh", "-c", "printf 'third\\n'"),
                outputFile,
                append = false,
                modernProcessApi = true,
            )
            assertTrue(truncated.waitFor(1, TimeUnit.SECONDS))
            assertEquals("third\n", outputFile.readText())
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun `desktop JVM selects the modern process API`() {
        assertTrue(probeModernProcessApi())
        assertTrue(hasModernProcessApi)
    }
}
