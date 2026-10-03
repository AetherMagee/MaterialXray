package com.material.xray.core.app

/** An installed app as routing sees it: which package, in which profile, under which uid. */
data class RoutableApp(
    val packageName: String,
    val profileId: Int,
    val uid: Int,
) {
    val appKey: String = appKey(profileId, packageName)
}

data class RoutableAppSnapshot(
    val apps: List<RoutableApp>,
    val profileIds: Set<Int>,
)

/**
 * The installed apps that per-app routing applies to. On Android, `:core:android`'s `AppInventory`
 * reads them from the package manager, in every user profile the app can see.
 */
interface AppInventorySource {
    suspend fun loadRoutingSnapshot(): RoutableAppSnapshot
}
