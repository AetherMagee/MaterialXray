package com.material.xray.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootlessOrphanStopperTest {
    @Test
    fun `matches app Xray command with its exact config path`() {
        assertTrue(
            isOwnedXrayCommand(
                listOf("/data/app/pkg/lib/arm64/libxray.so", "run", "-c", "/data/user/0/pkg/files/config.json"),
                "/data/user/0/pkg/files/config.json",
            ),
        )
    }

    @Test
    fun `rejects other commands and configs`() {
        assertFalse(isOwnedXrayCommand(listOf("/system/bin/sh", "-c", "/data/user/0/pkg/files/config.json"), "/data/user/0/pkg/files/config.json"))
        assertFalse(isOwnedXrayCommand(listOf("/data/app/pkg/lib/arm64/libxray.so", "run", "-c", "/other/config.json"), "/data/user/0/pkg/files/config.json"))
    }

    @Test
    fun `reads effective UID from proc status`() {
        assertEquals(10234, effectiveUidFromStatus(listOf("Name:\txray", "Uid:\t10234\t10234\t10234\t10234")))
        assertEquals(null, effectiveUidFromStatus(listOf("Name:\txray")))
    }
}
