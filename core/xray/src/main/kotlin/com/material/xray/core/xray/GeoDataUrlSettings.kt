package com.material.xray.core.xray

import kotlinx.coroutines.flow.Flow

/** Where [GeoDataManager] downloads the default geodata from. An empty value means the default URL. */
interface GeoDataUrlSettings {
    val geoipUrl: Flow<String>
    val geositeUrl: Flow<String>
}

/** The geodata the app ships with when the user has not picked other sources. */
object GeoDataDefaults {
    const val GEOIP_URL = "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"
    const val GEOSITE_URL = "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"
}
