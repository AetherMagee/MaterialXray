package com.material.xray.core.common.io

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Whole-file replacement that a crash cannot leave half written, in place of
 * `android.util.AtomicFile` (same file names, so it reads what that class wrote).
 *
 * [write] writes `<name>.new`, syncs it to disk and renames it over [baseFile], so a reader sees
 * the old content or the new, never a mix. If writing fails, the old content stays and the
 * temporary file is removed. A `<name>.bak` left by an interrupted write of the pre-Android-11
 * `AtomicFile` is restored before reading or writing, as `AtomicFile` does.
 *
 * Like `AtomicFile`, this does not lock: callers serialise their own writes.
 */
class AtomicFile(val baseFile: File) {
    private val newFile = File(baseFile.path + ".new")
    private val legacyBackupFile = File(baseFile.path + ".bak")

    fun exists(): Boolean = baseFile.exists() || legacyBackupFile.exists()

    /** Opens the last committed content; throws [java.io.FileNotFoundException] when there is none. */
    fun openRead(): FileInputStream {
        restoreLegacyBackup()
        return FileInputStream(baseFile)
    }

    /** Replaces the content with what [block] writes. If [block] throws, the old content stays. */
    fun write(block: (OutputStream) -> Unit) {
        restoreLegacyBackup()
        baseFile.parentFile?.mkdirs()
        var committed = false
        try {
            FileOutputStream(newFile).use { output ->
                block(output)
                output.flush()
                output.fd.sync()
            }
            replaceAtomically(newFile, baseFile)
            committed = true
        } finally {
            if (!committed) newFile.delete()
        }
    }

    fun writeBytes(bytes: ByteArray) = write { it.write(bytes) }

    fun delete() {
        baseFile.delete()
        newFile.delete()
        legacyBackupFile.delete()
    }

    private fun restoreLegacyBackup() {
        if (legacyBackupFile.exists()) replaceAtomically(legacyBackupFile, baseFile)
    }
}

/**
 * Renames [source] over [target]. `File.renameTo` is an atomic replace on POSIX and works on every
 * Android API level, while `java.nio.file` needs API 26 (minSdk is 24), so it goes first.
 * `Files.move` covers hosts whose rename refuses to replace, such as Windows.
 */
internal fun replaceAtomically(
    source: File,
    target: File,
    rename: (File, File) -> Boolean = File::renameTo,
) {
    if (rename(source, target)) return
    Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
