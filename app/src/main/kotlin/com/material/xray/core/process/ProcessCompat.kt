package com.material.xray.core.process

import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun Process.isAliveCompat(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    isAlive
} else {
    isAliveLegacy()
}

internal fun Process.destroyForciblyCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        destroyForcibly()
    } else {
        destroy()
    }
}

internal fun Process.waitForCompat(
    timeout: Long,
    unit: TimeUnit,
): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return waitFor(timeout, unit)

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

internal class RedirectedProcess private constructor(
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
        ): RedirectedProcess {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
