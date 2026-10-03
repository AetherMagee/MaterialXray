package com.material.xray.data.parser

import java.io.IOException
import java.util.UUID

/** The app version and hardware id that subscription requests identify the device with. */
interface SubscriptionDeviceIdentity {
    fun appVersion(): String

    fun hardwareId(): String
}

/**
 * Picks the hardware id to send: the platform's device id unless it is blank or the well-known
 * broken Android value, otherwise a random token that is generated once and then reused.
 */
fun resolveSubscriptionHardwareId(
    androidId: String?,
    readStored: () -> String?,
    store: (String) -> Boolean,
    generate: () -> String = { UUID.randomUUID().toString() },
): String {
    val normalizedId = androidId?.trim()
    if (!normalizedId.isNullOrBlank() && !normalizedId.equals("9774d56d682e549c", ignoreCase = true)) {
        return normalizedId
    }
    readStored()?.takeIf { it.isNotBlank() }?.let { return it }
    return generate().also { generated ->
        if (!store(generated)) throw IOException("Unable to persist subscription hardware ID")
    }
}
