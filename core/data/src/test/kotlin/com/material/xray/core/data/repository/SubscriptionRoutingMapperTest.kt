package com.material.xray.core.data.repository

import com.material.xray.core.database.entity.SubscriptionEntity
import com.material.xray.core.model.RoutingRule
import com.material.xray.core.model.SubscriptionRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionRoutingMapperTest {
    @Test
    fun `subscription routing round trip preserves provider config`() {
        val routing = SubscriptionRouting(
            rules = listOf(
                RoutingRule(
                    id = "provider-direct",
                    name = "Provider direct",
                    outboundTag = "direct",
                    domains = listOf("domain:example"),
                ),
            ),
            domainStrategy = "IPIfNonMatch",
            domainMatcher = "hybrid",
            fallbackOutboundTag = "direct",
        )
        val entity = SubscriptionEntity(name = "Provider", url = "https://example.com/sub")
            .withSubscriptionRouting(routing)

        assertEquals(routing, entity.toSubscriptionRouting())
    }

    @Test
    fun `invalid persisted subscription routing is ignored`() {
        val entity = SubscriptionEntity(
            name = "Provider",
            url = "https://example.com/sub",
            providerRouting = "invalid",
        )

        assertNull(entity.toSubscriptionRouting())
    }
}
