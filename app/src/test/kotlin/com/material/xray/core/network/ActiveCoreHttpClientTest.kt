package com.material.xray.core.network

import java.io.File
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveCoreHttpClientTest {
    @Test
    fun `accepts only the configured socket inside the private directory`() {
        val privateDir = File("/data/user/0/com.material.xray/files/bin")
        val path = File(privateDir, "mxray-http.sock").path
        val config = config(path)

        assertEquals(path, privateHttpSocketPath(config, privateDir))
        assertNull(privateHttpSocketPath(config("/tmp/mxray-http.sock"), privateDir))
        assertNull(privateHttpSocketPath(config(path, tag = "other-inbound"), privateDir))
    }

    private fun config(path: String, tag: String = "mxray-http-in"): String = buildJsonObject {
        put(
            "inbounds",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("tag", tag)
                        put("listen", "$path,0666")
                    },
                )
            },
        )
    }.toString()
}
