package com.material.xray.ui.routing

import androidx.annotation.StringRes
import com.material.xray.R

internal enum class RoutingTab(@StringRes val titleResource: Int) {
    Rules(R.string.routing_tab_rules),
    Apps(R.string.routing_tab_apps),
}
