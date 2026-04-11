package com.materialxray.data.parser

import com.materialxray.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import javax.inject.Inject

class SubscriptionFetcher @Inject constructor(
    private val client: OkHttpClient,
) {
    private val parser = ShareLinkParser()

    suspend fun fetch(url: String): List<ServerConfig> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext emptyList()

        val decoded = try {
            Base64.getDecoder().decode(body.trim()).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            body
        }

        parser.parseMultiple(decoded)
    }
}
