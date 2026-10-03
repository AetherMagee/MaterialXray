package com.material.xray.core.format

import androidx.annotation.StringRes
import com.material.xray.R
import com.material.xray.core.common.format.TrafficMagnitude

/** Unit label for a throughput reading, such as `KiB/s`. */
@StringRes
fun TrafficMagnitude.rateUnit(): Int = when (this) {
    TrafficMagnitude.Bytes -> R.string.traffic_unit_bytes_per_second
    TrafficMagnitude.Kibibytes -> R.string.traffic_unit_kibibytes_per_second
    TrafficMagnitude.Mebibytes -> R.string.traffic_unit_mebibytes_per_second
    TrafficMagnitude.Gibibytes -> R.string.traffic_unit_gibibytes_per_second
    TrafficMagnitude.Tebibytes -> R.string.traffic_unit_tebibytes_per_second
}

/** Unit label for an amount of data, such as `KiB`. */
@StringRes
fun TrafficMagnitude.sizeUnit(): Int = when (this) {
    TrafficMagnitude.Bytes -> R.string.traffic_unit_bytes
    TrafficMagnitude.Kibibytes -> R.string.traffic_unit_kibibytes
    TrafficMagnitude.Mebibytes -> R.string.traffic_unit_mebibytes
    TrafficMagnitude.Gibibytes -> R.string.traffic_unit_gibibytes
    TrafficMagnitude.Tebibytes -> R.string.traffic_unit_tebibytes
}
