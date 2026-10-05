package com.material.xray.core.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.material.xray.core.common.platform.PlatformInfo
import com.material.xray.core.data.parser.SubscriptionDeviceIdentity
import com.material.xray.core.data.parser.SubscriptionFetcher
import com.material.xray.core.data.parser.SubscriptionHardwareIdRequiredException
import com.material.xray.core.database.AppDatabase
import com.material.xray.core.network.DirectHttpClient
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubscriptionHardwareIdConsentTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `cancel sends no hwid and does not store subscription or change preference`() = runBlocking {
        withRepository { repository, settings, requests ->
            settings.setSubscriptionSendHardwareId(false)
            var prompted = false
            val id = repository.add("Private", "https://example.com/sub", preferJson = false) {
                prompted = true
                assertTrue(repository.getAllSubscriptions().isEmpty())
                false
            }
            assertTrue(prompted)
            assertNull(id)
            assertTrue(repository.getAllSubscriptions().isEmpty())
            assertFalse(settings.subscriptionSendHardwareId.first())
            assertEquals(1, requests.size)
            assertNull(requests.single().header("x-hwid"))
        }
    }

    @Test
    fun `proceed refetches with hwid and remembers only subscription policy`() = runBlocking {
        withRepository { repository, settings, requests ->
            settings.setSubscriptionSendHardwareId(false)
            val id = requireNotNull(
                repository.add("Private", "https://example.com/sub", preferJson = false) {
                    assertTrue(repository.getAllSubscriptions().isEmpty())
                    true
                },
            )
            assertTrue(requireNotNull(repository.getById(id)).requiresHardwareId)
            assertFalse(settings.subscriptionSendHardwareId.first())
            assertEquals(listOf(null, "test-hwid"), requests.map { it.header("x-hwid") })
            repository.commitRefresh(requireNotNull(repository.prepareRefresh(id, "https://example.com/sub")))
            assertTrue(requireNotNull(repository.getById(id)).requiresHardwareId)
            repository.prepareRefresh(id, "https://example.com/sub")
            assertEquals("test-hwid", requests.last().header("x-hwid"))
        }
    }

    @Test
    fun `json endpoint requirement prompts without fetching another endpoint first`() = runBlocking {
        withRepository { repository, settings, requests ->
            settings.setSubscriptionSendHardwareId(false)
            var prompts = 0
            assertNull(
                repository.add("Private", "https://example.com/sub", preferJson = true) {
                    prompts++
                    false
                },
            )
            assertEquals(1, prompts)
            assertEquals(listOf("/sub/json"), requests.map { it.url.encodedPath })
            assertNull(requests.single().header("x-hwid"))
        }
    }

    @Test
    fun `already enabled global preference adds required subscription without prompting`() = runBlocking {
        withRepository { repository, settings, requests ->
            assertTrue(settings.subscriptionSendHardwareId.first())
            assertNotNull(
                repository.add("Allowed", "https://example.com/sub", preferJson = false) {
                    error("Already enabled HWID must not prompt")
                },
            )
            assertEquals("test-hwid", requests.single().header("x-hwid"))
        }
    }

    @Test
    fun `non-required subscription stays without hwid after switching away and back`() = runBlocking {
        withRepository { repository, settings, requests ->
            settings.setSubscriptionSendHardwareId(false)
            val requiredId = requireNotNull(repository.add("Required", "https://example.com/sub", preferJson = false) { true })
            val plainId = requireNotNull(
                repository.add("Plain", "https://example.com/plain", preferJson = false) {
                    error("A non-required subscription must not prompt")
                },
            )
            val requiredServer = repository.prepareRefresh(requiredId, "https://example.com/sub")!!.servers.single()
            val plainServer = repository.prepareRefresh(plainId, "https://example.com/plain")!!.servers.single()
            assertTrue(requireNotNull(repository.getById(requiredServer.subscriptionId)).requiresHardwareId)
            assertFalse(requireNotNull(repository.getById(plainServer.subscriptionId)).requiresHardwareId)
            assertNull(requests.last().header("x-hwid"))
            repository.prepareRefresh(requiredId, "https://example.com/sub")
            assertEquals("test-hwid", requests.last().header("x-hwid"))
            assertFalse(settings.subscriptionSendHardwareId.first())
        }
    }

    @Test
    fun `new hwid requirement during refresh cannot silently override global off`() = runBlocking {
        withRepository { repository, settings, requests ->
            settings.setSubscriptionSendHardwareId(false)
            val id = requireNotNull(
                repository.add("Private", "https://example.com/new-policy", preferJson = false) {
                    error("First response does not require HWID")
                },
            )
            try {
                repository.prepareRefresh(id, "https://example.com/new-policy")
                org.junit.Assert.fail("New HWID requirement must not be accepted without consent")
            } catch (_: SubscriptionHardwareIdRequiredException) {
                assertFalse(requireNotNull(repository.getById(id)).requiresHardwareId)
                assertFalse(settings.subscriptionSendHardwareId.first())
                assertTrue(requests.all { it.header("x-hwid") == null })
            }
        }
    }

    private suspend fun withRepository(block: suspend (SubscriptionRepository, SettingsRepository, MutableList<Request>) -> Unit) {
        val queryDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val database = Room.inMemoryDatabaseBuilder<AppDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(queryDispatcher)
            .build()
        val settings = SettingsRepository(PreferenceDataStores.settings { File(folder.root, "settings.preferences_pb") }) {}
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val required = request.url.encodedPath != "/plain" && (request.url.encodedPath != "/new-policy" || requests.size > 1)
            val allowed = !required || request.header("x-hwid") != null
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (allowed) 200 else 403)
                .message("HWID policy")
                .apply { if (required && !allowed) header("subscription-always-hwid-enable", "true") }
                .body((if (!allowed) "" else "vless://uuid@node.example:443#Node").toResponseBody())
                .build()
        }.build()
        val identity = object : SubscriptionDeviceIdentity {
            override fun appVersion() = "test"
            override fun hardwareId() = "test-hwid"
        }
        val platform = object : PlatformInfo {
            override val sdkInt = 34
            override val osName = "Android"
            override val osVersion = "14"
            override val manufacturer = "Test"
            override val model = "Device"
            override val device = "test"
            override val processId = 1
            override val uid = 10123
            override val buildFingerprint = "test"
            override val primaryAbi = "arm64-v8a"
        }
        try {
            block(
                SubscriptionRepository(database, database.subscriptionDao(), database.serverDao(), SubscriptionFetcher(DirectHttpClient(client), identity, platform), settings),
                settings,
                requests,
            )
        } finally {
            database.close()
            queryDispatcher.close()
        }
    }
}
