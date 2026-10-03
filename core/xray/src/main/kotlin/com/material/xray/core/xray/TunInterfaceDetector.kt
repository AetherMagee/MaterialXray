package com.material.xray.core.xray

import java.net.NetworkInterface

object TunInterfaceDetector {
    fun isInterfaceUp(name: String): Boolean = runCatching { NetworkInterface.getByName(name)?.isUp == true }
        .getOrDefault(false)
}
