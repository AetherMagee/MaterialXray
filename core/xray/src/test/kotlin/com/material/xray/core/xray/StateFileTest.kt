package com.material.xray.core.xray

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateFileTest {
    @Test
    fun `reads back what it wrote and tells a missing file from a corrupt one`() = withTempDir { dir ->
        val stateFile = StateFile(TestPaths(dir))
        assertEquals(XrayStateReadResult.Absent, stateFile.readResult())

        val state = XrayState(xrayPid = 42, tunName = "xray1", timestamp = 1)
        stateFile.write(state)
        assertEquals(XrayStateReadResult.Present(state), stateFile.readResult())

        File(dir, "state.json").writeText("{")
        assertEquals(XrayStateReadResult.Unreadable, stateFile.readResult())

        stateFile.delete()
        assertEquals(XrayStateReadResult.Absent, stateFile.readResult())
    }

    @Test
    fun `restores the backup an interrupted legacy AtomicFile write left behind`() = withTempDir { dir ->
        val state = XrayState(xrayPid = 7, timestamp = 1)
        StateFile(TestPaths(dir)).write(state)
        assertTrue(File(dir, "state.json").renameTo(File(dir, "state.json.bak")))

        assertEquals(XrayStateReadResult.Present(state), StateFile(TestPaths(dir)).readResult())
    }

    private class TestPaths(override val filesDir: File) : XrayPaths {
        override val nativeLibraryDir: File? = null
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("state-file-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
