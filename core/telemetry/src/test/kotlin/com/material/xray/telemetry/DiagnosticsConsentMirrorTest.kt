package com.material.xray.telemetry

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsConsentMirrorTest {
    @Test
    fun `missing or malformed mirror is disabled`() {
        val directory = Files.createTempDirectory("diagnostics-consent-test").toFile()
        val file = directory.resolve("enabled")

        try {
            val mirror = DiagnosticsConsentMirror(file)
            assertFalse(mirror.isEnabled())

            file.writeText("not-a-boolean")
            assertFalse(mirror.isEnabled())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `enabled state is persisted synchronously`() {
        val directory = Files.createTempDirectory("diagnostics-consent-test").toFile()
        val file = directory.resolve("enabled")

        try {
            val mirror = DiagnosticsConsentMirror(file)
            mirror.setEnabled(true)
            assertTrue(DiagnosticsConsentMirror(file).isEnabled())

            mirror.setEnabled(false)
            assertFalse(DiagnosticsConsentMirror(file).isEnabled())
        } finally {
            directory.deleteRecursively()
        }
    }
}
