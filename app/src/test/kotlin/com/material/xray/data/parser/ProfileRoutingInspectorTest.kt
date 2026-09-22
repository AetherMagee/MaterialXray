package com.material.xray.data.parser

import org.junit.Assert.assertEquals
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
}
