package com.material.xray.core.model

object GeoDataUpdateInterval {
    const val DEFAULT_HOURS = 24
    const val MIN_HOURS = 1
    const val MAX_HOURS = 720

    fun isValid(hours: Int): Boolean = hours == 0 || hours in MIN_HOURS..MAX_HOURS

    fun normalize(hours: Int?): Int = hours?.takeIf(::isValid) ?: DEFAULT_HOURS
}
