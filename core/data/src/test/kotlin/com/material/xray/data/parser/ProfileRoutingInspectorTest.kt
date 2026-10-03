package com.material.xray.data.parser

import com.material.xray.model.ProfileRoutingOverride
import com.material.xray.model.Protocol
import com.material.xray.model.RoutingRule
import com.material.xray.model.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRoutingInspectorTest {

    @Test
    fun `inspects outbound and balancer profile rules without changing them`() {
        val routing = ProfileRoutingInspector.inspect(
            """
            {
              "routing": {
                "domainStrategy": "IPIfNonMatch",
                "rules": [
                  {
                    "type": "field",
                    "domain": ["geosite:private"],
                    "outboundTag": "direct"
                  },
                  {
                    "type": "field",
                    "network": "tcp",
                    "balancerTag": "fallback"
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        requireNotNull(routing)
        assertEquals("IPIfNonMatch", routing.domainStrategy)
        assertEquals(2, routing.rules.size)
        assertEquals(ProfileRoutingTarget.Outbound("direct"), routing.rules[0].target)
        assertEquals(listOf("geosite:private"), routing.rules[0].domains)
        assertEquals(ProfileRoutingTarget.Balancer("fallback"), routing.rules[1].target)
        assertEquals(listOf("network"), routing.rules[1].additionalConditionFields)
        assertTrue(routing.rules[1].rawJson.contains("\"balancerTag\": \"fallback\""))
    }

    @Test
    fun `excludes catch-all transport rules used to select a profile outbound`() {
        val routing = ProfileRoutingInspector.inspect(
            """
            {
              "routing": {
                "rules": [
                  {"type":"field","network":"tcp,udp","outboundTag":"proxy-1"},
                  {"type":"field","network":["udp","tcp"],"balancerTag":"fallback"},
                  {"type":"field","network":"tcp","outboundTag":"direct"}
                ]
              }
            }
            """.trimIndent(),
        )

        requireNotNull(routing)
        assertEquals(1, routing.rules.size)
        assertEquals(ProfileRoutingTarget.Outbound("direct"), routing.rules.single().target)
    }

    @Test
    fun `returns null when profile has no routing object`() {
        assertNull(ProfileRoutingInspector.inspect("{\"outbounds\": []}"))
    }

    @Test
    fun `returns null for malformed profile JSON`() {
        assertNull(ProfileRoutingInspector.inspect("not-json"))
    }

    @Test
    fun `describes disabled and edited profile deltas as logical rules`() {
        val original = """{"type":"field","domain":["old.example"],"outboundTag":"proxy"}"""
        val disabled = serverConfig(
            rawConfig = config(original),
            override = ProfileRoutingOverride(original, 0, enabled = false),
        )
        val edited = serverConfig(
            rawConfig = config(original),
            override = ProfileRoutingOverride(
                original,
                0,
                replacement = RoutingRule("edited", "Edited", "direct", domains = listOf("new.example")),
            ),
        )

        val disabledRule = requireNotNull(ProfileRoutingInspector.inspect(disabled)).rules.single()
        val editedRule = requireNotNull(ProfileRoutingInspector.inspect(edited)).rules.single()

        assertFalse(disabledRule.enabled)
        assertTrue(disabledRule.locallyModified)
        assertFalse(disabledRule.locallyEdited)
        assertEquals(listOf("new.example"), editedRule.domains)
        assertEquals("direct", editedRule.target?.tag)
        assertEquals("Edited", editedRule.editableRule?.name)
        assertTrue(editedRule.locallyEdited)
    }

    @Test
    fun `retains an orphaned delta for display`() {
        val missing = """{"type":"field","domain":["missing.example"],"outboundTag":"proxy"}"""
        val config = serverConfig(
            rawConfig = """{"outbounds":[]}""",
            override = ProfileRoutingOverride(missing, 0, enabled = false, orphaned = true),
        )

        val rules = requireNotNull(ProfileRoutingInspector.inspect(config)).rules

        assertEquals(1, rules.size)
        assertTrue(rules.single().orphaned)
        assertFalse(rules.single().enabled)
    }

    private fun serverConfig(rawConfig: String, override: ProfileRoutingOverride) = ServerConfig(
        protocol = Protocol.RAW,
        name = "Profile",
        address = "example.com",
        port = 443,
        password = "",
        rawConfigJson = rawConfig,
        profileRoutingOverrides = listOf(override),
    )

    private fun config(rule: String): String = """{"routing":{"rules":[$rule]}}"""
}
