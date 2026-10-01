package com.material.xray.model

enum class AppUpdateInterval(val hours: Int) {
    TwelveHours(12),
    OneDay(24),
    ThreeDays(72),
    OneWeek(168),
    ;

    companion object {
        val default = TwelveHours

        fun fromHours(hours: Int?): AppUpdateInterval = entries.find { it.hours == hours } ?: default
    }
}
