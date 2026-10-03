package com.material.xray.core.common.io

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val base by lazy { File(folder.root, "state.json") }
    private val atomicFile by lazy { AtomicFile(base) }

    private fun AtomicFile.readText() = openRead().use { it.readBytes().decodeToString() }

    @Test
    fun `write then read returns the content`() {
        atomicFile.writeBytes("first".toByteArray())

        assertTrue(atomicFile.exists())
        assertEquals("first", atomicFile.readText())
        assertEquals(listOf("state.json"), folder.root.list()!!.toList())
    }

    @Test
    fun `write replaces previous content`() {
        atomicFile.writeBytes("a much longer first value".toByteArray())
        atomicFile.writeBytes("second".toByteArray())

        assertEquals("second", atomicFile.readText())
    }

    @Test
    fun `failure mid-write keeps the old content and removes the temporary file`() {
        atomicFile.writeBytes("old".toByteArray())

        assertThrows(IOException::class.java) {
            atomicFile.write { output ->
                output.write("partial".toByteArray())
                throw IOException("disk full")
            }
        }

        assertEquals("old", atomicFile.readText())
        assertFalse(File(folder.root, "state.json.new").exists())
    }

    @Test
    fun `failed first write leaves no file`() {
        assertThrows(IllegalStateException::class.java) {
            atomicFile.write { error("encoding failed") }
        }

        assertFalse(atomicFile.exists())
        assertThrows(FileNotFoundException::class.java) { atomicFile.openRead() }
        assertTrue(folder.root.list()!!.isEmpty())
    }

    @Test
    fun `stale temporary file from a crash does not affect reads or writes`() {
        atomicFile.writeBytes("committed".toByteArray())
        File(folder.root, "state.json.new").writeText("torn")

        assertEquals("committed", atomicFile.readText())
        atomicFile.writeBytes("next".toByteArray())
        assertEquals("next", atomicFile.readText())
    }

    @Test
    fun `legacy backup from an interrupted write is restored`() {
        base.writeText("torn")
        File(folder.root, "state.json.bak").writeText("committed")

        assertEquals("committed", atomicFile.readText())
        assertFalse(File(folder.root, "state.json.bak").exists())
    }

    @Test
    fun `legacy backup alone still counts as existing`() {
        File(folder.root, "state.json.bak").writeText("committed")

        assertTrue(atomicFile.exists())
        assertEquals("committed", atomicFile.readText())
    }

    @Test
    fun `write creates missing parent directories`() {
        val nested = AtomicFile(File(folder.root, "a/b/state.json"))

        nested.writeBytes("x".toByteArray())

        assertEquals("x", nested.readText())
    }

    @Test
    fun `delete removes the file and its companions`() {
        atomicFile.writeBytes("x".toByteArray())
        File(folder.root, "state.json.new").writeText("torn")
        File(folder.root, "state.json.bak").writeText("old")

        atomicFile.delete()

        assertFalse(atomicFile.exists())
        assertTrue(folder.root.list()!!.isEmpty())
    }

    @Test
    fun `replace falls back to an atomic move when rename refuses`() {
        val source = folder.newFile("source").apply { writeText("new") }
        val target = folder.newFile("target").apply { writeText("old") }

        replaceAtomically(source, target, rename = { _, _ -> false })

        assertEquals("new", target.readText())
        assertFalse(source.exists())
    }
}
