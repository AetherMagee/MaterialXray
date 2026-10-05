package com.material.xray.core.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavKeySerializationTest {
    private val keys: List<AppNavKey> = TopLevelKeys + listOf(
        DnsSettingsKey,
        XrayCoreSettingsKey,
        ConfigViewerKey(ConfigViewerTarget.Running),
        ConfigViewerKey(ConfigViewerTarget.Server(serverId = 42, name = "Tokyo \"edge\"")),
        RoutingRuleViewerKey(payload = """{"ruleId":7,"title":"Block ads"}"""),
        RoutingRuleEditorKey(payload = """{"id":null,"domains":["example.com"]}"""),
    )

    @Test
    fun `every key round-trips through the serializer the back stack uses`() {
        val serializer = NavKeySerializer<NavKey>()
        keys.forEach { key ->
            assertEquals(key, Json.decodeFromString(serializer, Json.encodeToString(serializer, key)))
        }
    }

    @Test
    fun `a whole back stack round-trips`() {
        val serializer = NavBackStackSerializer(NavKeySerializer<NavKey>())
        val stack = NavBackStack<NavKey>(RoutingKey, RoutingRuleEditorKey(payload = "{}"))
        assertEquals(stack.toList(), Json.decodeFromString(serializer, Json.encodeToString(serializer, stack)).toList())
    }

    @Test
    fun `the selected tab round-trips`() {
        val serializer = MutableStateSerializer(NavKeySerializer<TopLevelKey>())
        TopLevelKeys.forEach { key ->
            assertEquals(key, Json.decodeFromString(serializer, Json.encodeToString(serializer, mutableStateOf(key))).value)
        }
    }
}
