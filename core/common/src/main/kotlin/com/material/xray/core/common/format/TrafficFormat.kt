package com.material.xray.core.common.format

import java.text.NumberFormat
import java.util.Locale

/**
 * Binary magnitude a byte count was scaled to. Callers pick their own unit wording, because the
 * notification labels throughput ("KiB/s") while the connection banner labels a size ("KiB").
 */
enum class TrafficMagnitude {
    Bytes,
    Kibibytes,
    Mebibytes,
    Gibibytes,
    Tebibytes,
}

data class ScaledTraffic(
    val value: String,
    val magnitude: TrafficMagnitude,
)

/**
 * Scales [bytes] down to the largest binary unit that keeps it above 1, formatting whole bytes
 * without a fraction and everything else with one decimal. Negative input is clamped to zero,
 * because the only source of a negative value here is a counter that went backwards across a
 * core restart.
 */
fun scaleBytes(bytes: Long, locale: Locale): ScaledTraffic {
    val magnitudes = TrafficMagnitude.entries
    var value = bytes.coerceAtLeast(0L).toDouble()
    var index = 0
    while (value >= BINARY_STEP && index < magnitudes.lastIndex) {
        value /= BINARY_STEP
        index++
    }
    val formatted = if (index == 0) {
        NumberFormat.getIntegerInstance(locale).format(value.toLong())
    } else {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(value)
    }
    return ScaledTraffic(value = formatted, magnitude = magnitudes[index])
}

private const val BINARY_STEP = 1024.0
