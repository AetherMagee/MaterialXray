package com.material.xray.core.android.telemetry

import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentryTelemetryClientTest {
    @Test
    fun `sanitizer removes unsafe event context`() {
        val event = SentryEvent().apply {
            request = Request()
            serverName = "device-name"
            logger = "unsafe.logger"
            message = Message().apply { formatted = "user-provided message" }
            exceptions = listOf(
                SentryException().apply {
                    type = "IllegalStateException"
                    value = "secret exception text"
                },
            )
            user = User().apply {
                id = "installation-id"
                email = "person@example.com"
                ipAddress = "192.0.2.1"
            }
        }

        sanitizeTelemetryEvent(event)

        assertNull(event.request)
        assertNull(event.serverName)
        assertNull(event.message)
        assertEquals("IllegalStateException", event.exceptions?.single()?.value)
        assertEquals("installation-id", event.user?.id)
        assertNull(event.user?.email)
        assertNull(event.user?.ipAddress)
    }
}
