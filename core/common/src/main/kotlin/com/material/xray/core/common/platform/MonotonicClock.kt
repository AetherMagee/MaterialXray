package com.material.xray.core.common.platform

/**
 * A clock for measuring durations and ageing cached values, in place of
 * `SystemClock.elapsedRealtime()`/`elapsedRealtimeNanos()`.
 *
 * Only differences between two readings mean anything. On Android, `:core:android` binds an
 * implementation on `SystemClock.elapsedRealtimeNanos()`, which keeps counting while the device
 * sleeps; [SystemMonotonicClock] (`System.nanoTime()`) pauses in deep sleep on Android, so it is
 * meant for other JVM hosts and tests.
 */
fun interface MonotonicClock {
    fun elapsedNanos(): Long
}

fun MonotonicClock.elapsedMillis(): Long = elapsedNanos() / NANOS_PER_MILLI

/** `System.nanoTime()`. */
object SystemMonotonicClock : MonotonicClock {
    override fun elapsedNanos(): Long = System.nanoTime()
}

private const val NANOS_PER_MILLI = 1_000_000L
