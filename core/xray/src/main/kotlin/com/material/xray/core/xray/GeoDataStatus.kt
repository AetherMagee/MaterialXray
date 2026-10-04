package com.material.xray.core.xray

/** The geodata a connection uses, and whether preparing it had to download anything. */
data class GeoDataStatus(
    val geoipUrl: String,
    val geositeUrl: String,
    val downloaded: Boolean,
)
