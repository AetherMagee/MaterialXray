package com.material.xray.core.xray

import java.io.File
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class XrayStatsConfigTest {
    @Test
    fun `filesystem API socket round trips through config`() {
        val endpoint = XrayApiEndpoint.FileSystemUnixSocket("/data/user/0/app/files/api.sock")
        val config = buildStatsApi(endpoint)

        assertEquals(endpoint.path, config.getValue("listen").jsonPrimitive.content)
        assertEquals(endpoint, parseXrayApiEndpoint("""{"api":$config}""", File("/data/user/0/app/files/bin")))
    }

    @Test
    fun `working directory API socket maps back to the core's working directory`() {
        val socket = coreSocket("/data/user/0/app/files/bin", "api.sock")
        val endpoint = XrayApiEndpoint.FileSystemUnixSocket(socket.path, socket.listenPath)
        val config = buildStatsApi(endpoint)

        assertEquals("/proc/self/cwd/sockets/api.sock", config.getValue("listen").jsonPrimitive.content)
        assertEquals(endpoint, parseXrayApiEndpoint("""{"api":$config}""", File("/data/user/0/app/files/bin")))
    }
}
