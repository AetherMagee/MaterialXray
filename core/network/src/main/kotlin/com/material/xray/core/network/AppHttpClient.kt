package com.material.xray.core.network

import okhttp3.OkHttpClient

/**
 * Source of the [OkHttpClient] used for the app's own HTTP requests. Implementations decide how
 * the request leaves the device (directly or through the active tunnel); callers must keep all
 * requests of one logical operation inside a single [use] call.
 */
interface AppHttpClient {
    suspend fun <T> use(block: suspend (OkHttpClient) -> T): T
}

/** Always hands out the given client; requests leave the device directly. */
class DirectHttpClient(private val client: OkHttpClient) : AppHttpClient {
    override suspend fun <T> use(block: suspend (OkHttpClient) -> T): T = block(client)
}
