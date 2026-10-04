package com.material.xray.core.xray

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElfHeaderTest {
    @Test
    fun `reads class and machine from an ELF header`() = withFile(elf(is64Bit = true, machine = 183)) { file ->
        val header = readElfHeader(file)

        assertEquals(ElfHeader(is64Bit = true, machine = 183), header)
        assertTrue(header!!.matchesAbi("arm64-v8a"))
        assertFalse(header.matchesAbi("x86_64"))
        assertFalse(header.matchesAbi("armeabi-v7a"))
    }

    @Test
    fun `32-bit executables run through the 32-bit linker`() = withFile(elf(is64Bit = false, machine = 40)) { file ->
        assertTrue(readElfHeader(file)!!.matchesAbi("armeabi-v7a"))
        assertEquals(listOf(SYSTEM_LINKER_32, file.absolutePath), xrayUserCommand(file))
    }

    @Test
    fun `other files are not ELF`() = withFile("#!/bin/sh\n".toByteArray()) { file ->
        assertNull(readElfHeader(file))
        assertNull(readElfHeader(File(file.parentFile, "missing")))
    }

    private fun elf(is64Bit: Boolean, machine: Int) = ByteArray(64).apply {
        byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()).copyInto(this)
        this[4] = if (is64Bit) 2 else 1
        this[5] = 1
        this[18] = (machine and 0xFF).toByte()
        this[19] = (machine shr 8).toByte()
    }

    private fun withFile(content: ByteArray, block: (File) -> Unit) {
        val dir = Files.createTempDirectory("elf-header-test").toFile()
        try {
            block(File(dir, "libxray.so").apply { writeBytes(content) })
        } finally {
            dir.deleteRecursively()
        }
    }
}
