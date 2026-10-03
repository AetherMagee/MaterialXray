package com.material.xray.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRoutingOverrideEngineTest {
    private val json = Json

    @Test
    fun `disabled override removes its original rule`() {
        val original = rule("provider.example", "proxy")
        val override = ProfileRoutingOverride(original, originalIndex = 0, enabled = false)

        val rules = appliedRules(config(original, rule("other.example", "direct")), listOf(override))

        assertEquals(1, rules.size)
        assertEquals("direct", rules.single()["outboundTag"]?.jsonPrimitive?.content)
    }

    @Test
    fun `edited override replaces original and supports OR expansion`() {
        val original = rule("provider.example", "proxy")
        val replacement = RoutingRule(
            id = "edited",
            name = "Edited",
            outboundTag = "direct",
            domains = listOf("example.com"),
            ips = listOf("geoip:private"),
            operator = RoutingRuleOperator.OR,
        )
        val override = ProfileRoutingOverride(original, 0, replacement = replacement)

        val rules = appliedRules(config(original), listOf(override))

        assertEquals(2, rules.size)
        assertEquals(setOf("domain", "ip"), rules.map { it.keys.single { key -> key !in setOf("type", "outboundTag") } }.toSet())
        assertTrue(rules.all { it["outboundTag"]?.jsonPrimitive?.content == "direct" })
    }

    @Test
    fun `refresh reconciliation marks missing originals orphaned`() {
        val retained = ProfileRoutingOverride(rule("retained.example", "proxy"), 0, enabled = false)
        val removed = ProfileRoutingOverride(rule("removed.example", "proxy"), 1, enabled = false)

        val reconciled = ProfileRoutingOverrideEngine.reconcile(config(retained.originalRuleJson), listOf(retained, removed))

        assertFalse(reconciled[0].orphaned)
        assertTrue(reconciled[1].orphaned)
    }

    @Test
    fun `reenabling an unedited rule removes its delta`() {
        val original = rule("provider.example", "proxy")
        val disabled = ProfileRoutingOverride(original, 0, enabled = false)

        val overrides = ProfileRoutingOverrideEngine.replace(
            overrides = listOf(disabled),
            originalRuleJson = original,
            originalIndex = 0,
            replacement = null,
            enabled = true,
        )

        assertTrue(overrides.isEmpty())
    }

    private fun appliedRules(raw: String, overrides: List<ProfileRoutingOverride>) = json
        .parseToJsonElement(ProfileRoutingOverrideEngine.apply(raw, overrides))
        .jsonObject.getValue("routing").jsonObject.getValue("rules").jsonArray
        .map { it.jsonObject }

    private fun config(vararg rules: String): String = """{"routing":{"rules":[${rules.joinToString(",")}]}}"""

    private fun rule(domain: String, outbound: String): String = """{"type":"field","domain":["$domain"],"outboundTag":"$outbound"}"""
}
