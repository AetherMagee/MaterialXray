package com.material.xray.service

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {

    @Test
    fun `package replacement restores a surviving runtime before reconnecting`() {
        assertTrue(
            shouldRecoverAfterPackageReplacement(
                Intent.ACTION_MY_PACKAGE_REPLACED,
                autoConnect = false,
                hasRecordedRuntime = true,
            ),
        )
        assertTrue(
            shouldRecoverAfterPackageReplacement(
                Intent.ACTION_MY_PACKAGE_REPLACED,
                autoConnect = true,
                hasRecordedRuntime = false,
            ),
        )
        assertFalse(
            shouldRecoverAfterPackageReplacement(
                Intent.ACTION_BOOT_COMPLETED,
                autoConnect = true,
                hasRecordedRuntime = true,
            ),
        )
    }
}
