package com.material.xray.core.xraycore

import com.material.xray.core.model.AppUpdateInterval
import com.material.xray.core.xray.XrayPaths
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class XrayCoreUpdateSettingsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `core checks default to one week without changing app checks`() {
        assertEquals(XrayCoreUpdateInterval.OneWeek, store().settings.value.interval)
        assertEquals(AppUpdateInterval.TwelveHours, AppUpdateInterval.default)
    }

    @Test
    fun `old settings preserve update choices and default to one week`() {
        File(temporary.root, "xray-core-updates.json").writeText(
            """{"periodicChecks":true,"action":"Install","notifiedTag":"v26.9.30","autoInstalledId":"old-core"}""",
        )

        val settings = store().settings.value
        assertTrue(settings.periodicChecks)
        assertEquals(XrayCoreUpdateAction.Install, settings.action)
        assertEquals("v26.9.30", settings.notifiedTag)
        assertEquals("old-core", settings.autoInstalledId)
        assertEquals(XrayCoreUpdateInterval.OneWeek, settings.interval)
    }

    @Test
    fun `each interval survives reopening and toggling checks`() {
        val store = store()
        XrayCoreUpdateInterval.entries.forEach { interval ->
            store.update { it.copy(periodicChecks = true, intervalHours = interval.hours) }
            assertEquals(interval, store().settings.value.interval)
            store.update { it.copy(periodicChecks = false) }
            assertEquals(interval, store().settings.value.interval)
        }
    }

    @Test
    fun `invalid or removed interval falls back to one week without losing other settings`() {
        listOf(0, 12, 24).forEach { hours ->
            File(temporary.root, "xray-core-updates.json").writeText("""{"periodicChecks":true,"intervalHours":$hours}""")
            val settings = store().settings.value
            assertTrue(settings.periodicChecks)
            assertEquals(XrayCoreUpdateInterval.OneWeek, settings.interval)
        }
    }

    private fun store() = XrayCoreUpdateSettingsStore(
        object : XrayPaths {
            override val filesDir: File = temporary.root
            override val cacheDir: File = temporary.root
            override val nativeLibraryDir: File? = null
        },
    )
}
