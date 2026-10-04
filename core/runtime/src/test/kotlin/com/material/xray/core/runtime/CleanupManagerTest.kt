package com.material.xray.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupManagerTest {
    @Test
    fun `owned process cleanup is valid shell syntax`() {
        val command = ownedProcessStopCommand("/data/user/0/app/files/config.json", appGid = 10123, persistedPid = 42)

        assertEquals(0, ProcessBuilder("sh", "-n", "-c", command).start().waitFor())
    }

    @Test
    fun `cleanup verifies config ownership before signaling candidate pids`() {
        val command = ownedProcessStopCommand("/data/user/0/app/files/config.json", appGid = 10123, persistedPid = 42)

        assertTrue(command.indexOf("kill \"\$pid\"") > command.indexOf("is_owned()"))
        assertTrue(command.contains("/proc/\$1/cmdline"))
        assertTrue(command.contains("cat -v \"/proc/\$1/cmdline\""))
        assertFalse(command.contains("tr "))
        assertTrue(command.contains("*'/data/user/0/app/files/config.json'*"))
        assertTrue(command.contains("$ROOT_CORE_UID:10123:10123|0:10123:10123) return 0"))
        assertTrue(command.contains("candidates='42'"))
        assertFalse(command.contains("return 2"))
        assertTrue(command.contains("if is_owned \"\$pid\"; then kill"))
        assertTrue(command.contains("if is_owned \"\$pid\"; then kill -9"))
    }

    @Test
    fun `cleanup also finds a core started by a release that ran the extracted binary`() {
        val command = ownedProcessStopCommand("/data/user/0/app/files/config.json", appGid = 10123, persistedPid = null)

        assertTrue(command.contains("pidof libxray.so xray "))
    }

    @Test
    fun `ownership check recognises a core started before the sandbox by its config path`() {
        val config = "/tmp/mxray test/files/config.json"
        val core = ProcessBuilder("sh", "-c", "echo \$\$; sleep 30; : '$config'").start()
        try {
            val pid = core.inputStream.bufferedReader().readLine()
            fun owns(path: String): Boolean = ProcessBuilder(
                "sh",
                "-c",
                rootCoreOwnershipFunction(path, appGid = 10123) + "is_owned $pid",
            ).start().waitFor() == 0

            assertTrue(owns(config))
            assertFalse(owns("/tmp/other app/files/config.json"))
        } finally {
            core.destroyForcibly()
        }
    }

    @Test
    fun `cleanup never signals the persisted pid directly`() {
        val command = ownedProcessStopCommand("/data/user/0/app/files/config.json", appGid = 10123, persistedPid = 42)

        assertFalse(command.contains("kill 42"))
        assertFalse(command.contains("kill -9 42"))
    }

    @Test
    fun `batched cleanup reports every stage even after one exits with a failure`() {
        val command = cleanupBatchCommand(
            listOf(
                "refresh() { exit 3; }; refresh; echo unreachable",
                "printf 'no newline'",
                "sleep 0.05 # trailing comment",
            ),
        )
        val process = ProcessBuilder("sh", "-c", command).start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(1, process.waitFor())
        assertFalse(output.contains("unreachable"))
        val reports = parseCleanupStageReports(output)
        assertEquals(listOf(0, 1, 2), reports.keys.sorted())
        assertEquals(3, reports.getValue(0).exitCode)
        assertEquals(0, reports.getValue(1).exitCode)
        assertEquals(0, reports.getValue(2).exitCode)
        assertTrue(reports.getValue(2).durationMs >= 40)
        assertEquals(0, reports.getValue(2).durationMs % 10)
    }

    @Test
    fun `batched cleanup succeeds when every stage does`() {
        val command = cleanupBatchCommand(listOf("true", "status=0; exit \$status"))

        assertEquals(0, ProcessBuilder("sh", "-c", command).start().waitFor())
    }

    @Test
    fun `stage reports ignore unrelated output`() {
        val reports = parseCleanupStageReports("noise\n__MXRAY_CLEANUP_STAGE__ 1 0 20\nmore __MXRAY_CLEANUP_STAGE__ 2 0 10\n")

        assertEquals(mapOf(1 to CleanupStageReport(exitCode = 0, durationMs = 20)), reports)
    }
}
