package com.material.xray.core.xray

import com.material.xray.core.process.destroyForciblyCompat
import com.material.xray.core.process.waitForCompat
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Android Xray build, which both runtimes launch from the installer-extracted native library
 * directory, plus the helper that hands it a TUN interface in root mode.
 */
class XrayBinary(
    private val paths: XrayPaths,
) {
    private val binaryDir = File(paths.filesDir, "bin")
    val binaryPath: String? get() = nativeExecutablePath(XRAY_EXECUTABLE_NAME)
    val tunLauncherPath: String? get() = nativeExecutablePath(TUN_LAUNCHER_LIBRARY_NAME)

    fun ensureAvailable(): Boolean {
        binaryDir.mkdirs()
        removeLegacyRootBinary()
        return binaryPath != null && tunLauncherPath != null
    }

    fun readVersion(): String? {
        binaryDir.mkdirs()
        val binaryPath = binaryPath ?: return null

        return runCatching {
            val process = ProcessBuilder(binaryPath, "version")
                .directory(binaryDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["xray.location.asset"] = binaryDir.absolutePath
                    environment()["XRAY_LOCATION_ASSET"] = binaryDir.absolutePath
                }
                .start()

            if (!process.waitForCompat(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForciblyCompat()
                return@runCatching null
            }

            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.exitValue() == 0) parseXrayVersion(output) else null
        }.getOrNull()
    }

    fun configPath(): String = File(paths.filesDir, ACTIVE_CONFIG_FILE).absolutePath

    fun readConfig(): String? = File(configPath())
        .takeIf { it.isFile }
        ?.let { config -> runCatching { config.readText() }.getOrNull() }

    fun writeConfig(configJson: String) {
        File(paths.filesDir, ACTIVE_CONFIG_FILE).writeText(configJson)
    }

    /**
     * The config the user edited by hand, used verbatim in place of a generated one. Null when no
     * override is stored.
     */
    fun readOverrideConfig(): String? = File(paths.filesDir, ACTIVE_CONFIG_OVERRIDE_FILE)
        .takeIf { it.isFile }
        ?.let { override -> runCatching { override.readText() }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

    private fun nativeExecutablePath(name: String): String? = paths.nativeLibraryDir
        ?.resolve(name)
        ?.takeIf { it.isFile && it.canExecute() }
        ?.absolutePath

    // Versions that ran root mode on a separate Linux build extracted it here, along with the
    // install stamp that versioned it. Nothing reads either any more.
    private fun removeLegacyRootBinary() {
        File(binaryDir, "xray").delete()
        File(binaryDir, "version").delete()
    }

    private companion object {
        private const val VERSION_TIMEOUT_SECONDS = 2L
        private const val TUN_LAUNCHER_LIBRARY_NAME = "libxraytun.so"
    }
}

private val XRAY_VERSION_REGEX = Regex("^Xray\\s+v?([^\\s]+)")

/** The core's file name, which is also the process name `pidof` finds it by. */
const val XRAY_EXECUTABLE_NAME = "libxray.so"

const val ACTIVE_CONFIG_FILE = "config.json"

/** Sibling of [ACTIVE_CONFIG_FILE] holding a hand-edited config that replaces generation. */
internal const val ACTIVE_CONFIG_OVERRIDE_FILE = "config_override.json"

internal fun parseXrayVersion(output: String): String? = output.lineSequence()
    .mapNotNull { line -> XRAY_VERSION_REGEX.find(line.trim())?.groupValues?.getOrNull(1) }
    .firstOrNull()
