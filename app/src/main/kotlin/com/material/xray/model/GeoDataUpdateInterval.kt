package com.material.xray.model

object GeoDataUpdateInterval {
    const val DEFAULT_HOURS = 24
    const val MIN_HOURS = 1
    const val MAX_HOURS = 720

    fun isValid(hours: Int): Boolean = hours in MIN_HOURS..MAX_HOURS

    fun normalize(hours: Int?): Int = hours?.takeIf(::isValid) ?: DEFAULT_HOURS
}
