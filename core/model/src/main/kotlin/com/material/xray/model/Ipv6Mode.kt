package com.material.xray.model

/** Whether a session carries IPv6. */
enum class Ipv6Mode(val persistedValue: String) {
    Off("off"),

    /** IPv6 for sessions on networks where a probe through the server shows it working. */
    Auto("auto"),

    On("on"),
    ;

    companion object {
        val default = Auto

        fun fromValue(value: String?): Ipv6Mode = entries.firstOrNull { it.persistedValue == value } ?: default
    }
}
