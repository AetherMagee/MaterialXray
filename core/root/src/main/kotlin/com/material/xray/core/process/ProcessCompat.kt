package com.material.xray.core.process

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Whether this runtime has `Process.isAlive()`, `destroyForcibly()`, `waitFor(timeout, unit)` and
 * `ProcessBuilder.Redirect`. Android added them in API 26 (O), below the app's minSdk of 24; every
 * desktop JVM has them.
 *
 * Probed instead of read from `Build.VERSION.SDK_INT` so this module stays platform-free and the
 * helpers below need no `PlatformInfo` from their callers. If any member is missing, every helper
 * takes its legacy path, which works on any runtime.
 */
internal val hasModernProcessApi: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) { probeModernProcessApi() }

internal fun probeModernProcessApi(): Boolean = try {
    Process::class.java.getMethod("isAlive")
    Process::class.java.getMethod("destroyForcibly")
    Process::class.java.getMethod("waitFor", Long::class.javaPrimitiveType, TimeUnit::class.java)
    Class.forName("java.lang.ProcessBuilder\$Redirect").getMethod("appendTo", File::class.java)
    true
} catch (_: ReflectiveOperationException) {
    false
}

internal fun Process.isAliveCompat(): Boolean = if (hasModernProcessApi) {
    isAlive
} else {
    isAliveLegacy()
}

fun Process.destroyForciblyCompat() {
    if (hasModernProcessApi) {
        destroyForcibly()
    } else {
        destroy()
    }
}

fun Process.waitForCompat(
    timeout: Long,
    unit: TimeUnit,
): Boolean {
    if (hasModernProcessApi) return waitFor(timeout, unit)

    return waitForLegacy(timeout, unit)
}

internal fun Process.isAliveLegacy(): Boolean = try {
    exitValue()
    false
} catch (_: IllegalThreadStateException) {
    true
}

internal fun Process.waitForLegacy(timeout: Long, unit: TimeUnit): Boolean {
    val timeoutNanos = unit.toNanos(timeout)
    val startedAt = System.nanoTime()
    while (isAliveLegacy()) {
        val elapsedNanos = System.nanoTime() - startedAt
        if (elapsedNanos >= timeoutNanos) return false
        val remainingMillis = TimeUnit.NANOSECONDS.toMillis(timeoutNanos - elapsedNanos)
        Thread.sleep(remainingMillis.coerceIn(1L, PROCESS_POLL_INTERVAL_MS))
    }
    return true
}

class RedirectedProcess private constructor(
    private val process: Process,
    private val outputPump: Thread?,
) {
    fun isAlive(): Boolean = process.isAliveCompat()

    fun destroy() = process.destroy()

    fun destroyForcibly() = process.destroyForciblyCompat()

    fun waitFor(timeout: Long, unit: TimeUnit): Boolean = process.waitForCompat(timeout, unit).also { exited ->
        if (exited) awaitOutput()
    }

    fun exitValue(): Int = process.exitValue()

    fun awaitOutput() {
        outputPump?.join(OUTPUT_PUMP_JOIN_TIMEOUT_MS)
    }

    companion object {
        fun start(
            builder: ProcessBuilder,
            outputFile: File,
            append: Boolean,
        ): RedirectedProcess = start(builder, outputFile, append, hasModernProcessApi)

        internal fun start(
            builder: ProcessBuilder,
            outputFile: File,
            append: Boolean,
            modernProcessApi: Boolean,
        ): RedirectedProcess {
            if (modernProcessApi) {
                val redirect = if (append) {
                    ProcessBuilder.Redirect.appendTo(outputFile)
                } else {
                    ProcessBuilder.Redirect.to(outputFile)
                }
                return RedirectedProcess(builder.redirectOutput(redirect).start(), outputPump = null)
            }

            val process = builder.start()
            val output = try {
                FileOutputStream(outputFile, append)
            } catch (error: IOException) {
                process.destroy()
                throw error
            }
            val outputPump = Thread({
                runCatching {
                    process.inputStream.use { input ->
                        output.use(input::copyTo)
                    }
                }
            }, "process-output-${process.hashCode()}").apply {
                isDaemon = true
                start()
            }
            return RedirectedProcess(process, outputPump)
        }
    }
}

private const val PROCESS_POLL_INTERVAL_MS = 25L
private const val OUTPUT_PUMP_JOIN_TIMEOUT_MS = 500L
