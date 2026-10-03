package com.material.xray.core.network

import kotlinx.coroutines.flow.Flow

/** The setting that decides whether [ActiveCoreHttpClient] sends the app's own requests through Xray. */
interface CoreTrafficRoutingSetting {
    val routeMxrayTrafficThroughXray: Flow<Boolean>
}
