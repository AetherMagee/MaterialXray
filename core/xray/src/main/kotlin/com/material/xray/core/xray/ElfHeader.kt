package com.material.xray.core.xray

import java.io.File
import java.io.IOException

/** The parts of an ELF header that tell which device an executable is for. */
data class ElfHeader(val is64Bit: Boolean, val machine: Int)

/** Reads [file]'s ELF header, or null when it is not an ELF file. */
fun readElfHeader(file: File): ElfHeader? {
    val header = ByteArray(ELF_HEADER_PREFIX_SIZE)
    val read = try {
        file.inputStream().use { it.readNBytesCompat(header) }
    } catch (_: IOException) {
        return null
    }
    if (read < ELF_HEADER_PREFIX_SIZE || !header.copyOfRange(0, ELF_MAGIC.size).contentEquals(ELF_MAGIC)) return null
    val is64Bit = when (header[ELF_CLASS_OFFSET].toInt()) {
        ELF_CLASS_32 -> false
        ELF_CLASS_64 -> true
        else -> return null
    }
    if (header[ELF_DATA_OFFSET].toInt() != ELF_DATA_LITTLE_ENDIAN) return null
    val machine = (header[ELF_MACHINE_OFFSET].toInt() and BYTE_MASK) or
        ((header[ELF_MACHINE_OFFSET + 1].toInt() and BYTE_MASK) shl Byte.SIZE_BITS)
    return ElfHeader(is64Bit, machine)
}

/** Whether [header] describes an executable for Android ABI [abi]. */
fun ElfHeader.matchesAbi(abi: String): Boolean = ABI_MACHINES[abi]?.let { (bits64, machine) ->
    is64Bit == bits64 && this.machine == machine
} == true

/**
 * The command that runs an executable from the app's data directory as the app's own uid. Android
 * refuses to execute such a file directly, but the system linker may load it.
 */
fun xrayUserCommand(executable: File): List<String> {
    val linker = if (readElfHeader(executable)?.is64Bit == false) SYSTEM_LINKER_32 else SYSTEM_LINKER_64
    return listOf(linker, executable.absolutePath)
}

const val SYSTEM_LINKER_64 = "/system/bin/linker64"
const val SYSTEM_LINKER_32 = "/system/bin/linker"

private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val read = read(buffer, total, buffer.size - total)
        if (read < 0) break
        total += read
    }
    return total
}

private val ELF_MAGIC = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
private const val ELF_HEADER_PREFIX_SIZE = 20
private const val ELF_CLASS_OFFSET = 4
private const val ELF_DATA_OFFSET = 5
private const val ELF_MACHINE_OFFSET = 18
private const val ELF_CLASS_32 = 1
private const val ELF_CLASS_64 = 2
private const val ELF_DATA_LITTLE_ENDIAN = 1
private const val BYTE_MASK = 0xFF
private const val EM_386 = 3
private const val EM_ARM = 40
private const val EM_X86_64 = 62
private const val EM_AARCH64 = 183
private val ABI_MACHINES = mapOf(
    "arm64-v8a" to (true to EM_AARCH64),
    "x86_64" to (true to EM_X86_64),
    "armeabi-v7a" to (false to EM_ARM),
    "x86" to (false to EM_386),
)
