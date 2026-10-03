package com.material.xray.core.common.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformInfoTest {
    private data class FakePlatformInfo(
        override val sdkInt: Int = 34,
        override val osName: String = "Android",
        override val osVersion: String = "14",
        override val manufacturer: String = "Google",
        override val model: String = "Pixel 8",
        override val device: String = "shiba",
        override val processId: Int = 1234,
    ) : PlatformInfo

    @Test
    fun `atLeast compares against the API level`() {
        val platform = FakePlatformInfo(sdkInt = 29)

        assertTrue(platform.atLeast(26))
        assertTrue(platform.atLeast(29))
        assertFalse(platform.atLeast(30))
    }

    @Test
    fun `device model joins trimmed manufacturer and model`() {
        assertEquals("Google Pixel 8", FakePlatformInfo(manufacturer = " Google ", model = "Pixel 8 ").deviceModel)
        assertEquals("Pixel 8", FakePlatformInfo(manufacturer = " ").deviceModel)
        assertEquals("Google", FakePlatformInfo(model = "").deviceModel)
    }

    @Test
    fun `device model falls back to device, then OS name`() {
        assertEquals("shiba", FakePlatformInfo(manufacturer = "", model = "").deviceModel)
        assertEquals("Android", FakePlatformInfo(manufacturer = "", model = "", device = " ").deviceModel)
    }

    @Test
    fun `JVM platform passes every API check and reports the current process`() {
        assertEquals(PlatformInfo.NOT_ANDROID_SDK_INT, JvmPlatformInfo.sdkInt)
        assertTrue(JvmPlatformInfo.atLeast(Int.MAX_VALUE))
        assertEquals(ProcessHandle.current().pid().toInt(), JvmPlatformInfo.processId)
        assertEquals(System.getProperty("os.name"), JvmPlatformInfo.osName)
        assertEquals(JvmPlatformInfo.osName, JvmPlatformInfo.deviceModel)
    }
}
