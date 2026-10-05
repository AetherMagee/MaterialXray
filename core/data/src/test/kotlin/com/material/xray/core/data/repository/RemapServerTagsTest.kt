package com.material.xray.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class RemapServerTagsTest {
    @Test
    fun `server-derived tags follow the new ids in one pass`() {
        val config = """{"rules":[{"inboundTag":["app-in-1","app-in-forced-2"],"outboundTag":"app-proxy-2"},""" +
            """{"inboundTag":["app-in-default-selected"],"outboundTag":"app-proxy-forced-1"},{"outboundTag":"app-proxy-9"}]}"""

        val remapped = remapServerTags(config, mapOf(1L to 2L, 2L to 3L))

        assertEquals(
            """{"rules":[{"inboundTag":["app-in-2","app-in-forced-3"],"outboundTag":"app-proxy-3"},""" +
                """{"inboundTag":["app-in-default-selected"],"outboundTag":"app-proxy-forced-2"},{"outboundTag":"app-proxy-9"}]}""",
            remapped,
        )
    }
}
