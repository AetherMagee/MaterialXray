package com.material.xray.data.repository

import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.model.ProfileRoutingOverride
import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRoutingOverrideMergeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `refresh carries delta to same named profile and reconciles it`() {
        val originalRule = """{"type":"field","domain":["old.example"],"outboundTag":"proxy"}"""
        val existing = server(
            id = 9,
            name = "Profile",
            rawConfig = config(originalRule),
            overrides = listOf(ProfileRoutingOverride(originalRule, 0, enabled = false)),
        )
        val fetched = server(id = 0, name = "Profile", rawConfig = config(originalRule))

        val merged = carryProfileRoutingOverridesInto(listOf(existing), listOf(fetched), json).single()
        val mergedConfig = json.decodeFromString<ServerConfig>(merged.configJson)

        assertTrue(merged.edited)
        assertFalse(mergedConfig.profileRoutingOverrides.single().orphaned)
    }

    @Test
    fun `refresh keeps a missing original as an orphaned delta`() {
        val originalRule = """{"type":"field","domain":["old.example"],"outboundTag":"proxy"}"""
        val existing = server(
            id = 9,
            name = "Profile",
            rawConfig = config(originalRule),
            overrides = listOf(ProfileRoutingOverride(originalRule, 0, enabled = false)),
        )
        val fetched = server(
            id = 0,
            name = "Profile",
            rawConfig = """{"outbounds":[]}""",
        )

        val merged = carryProfileRoutingOverridesInto(listOf(existing), listOf(fetched), json).single()
        val mergedConfig = json.decodeFromString<ServerConfig>(merged.configJson)

        assertTrue(mergedConfig.profileRoutingOverrides.single().orphaned)
    }

    private fun server(
        id: Long,
        name: String,
        rawConfig: String,
        overrides: List<ProfileRoutingOverride> = emptyList(),
    ): ServerEntity {
        val config = ServerConfig(
            protocol = Protocol.RAW,
            name = name,
            address = "example.com",
            port = 443,
            password = "",
            rawConfigJson = rawConfig,
            profileRoutingOverrides = overrides,
        )
        return ServerEntity(
            id = id,
            subscriptionId = 1,
            name = name,
            protocol = Protocol.RAW.name,
            address = config.address,
            port = config.port,
            configJson = json.encodeToString(config),
        )
    }

    private fun config(rule: String): String = """{"routing":{"rules":[$rule]}}"""
}
