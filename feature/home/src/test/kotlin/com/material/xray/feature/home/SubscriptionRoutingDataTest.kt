package com.material.xray.feature.home

import com.material.xray.core.data.repository.ProviderRoutingAvailability
import com.material.xray.core.data.repository.providerRoutingAvailability
import com.material.xray.core.data.repository.withSubscriptionAppRouting
import com.material.xray.core.data.repository.withSubscriptionRouting
import com.material.xray.core.database.entity.SubscriptionEntity
import com.material.xray.core.model.RoutingPolicyControl
import com.material.xray.core.model.SubscriptionAppRouting
import com.material.xray.core.model.SubscriptionAppRoutingMode
import com.material.xray.core.model.SubscriptionRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRoutingDataTest {
    @Test
    fun `manual apply excludes only the routing type managed by selected provider`() {
        val subscription = SubscriptionEntity(
            name = "Import source",
            url = "https://example.com/sub",
        ).withSubscriptionAppRouting(
            SubscriptionAppRouting(
                packageNames = listOf("com.example.app"),
                mode = SubscriptionAppRoutingMode.Direct,
            ),
        ).withSubscriptionRouting(SubscriptionRouting(emptyList()))

        val routing = subscription.manualRoutingData(
            policy = RoutingPolicyControl.SubscriptionProvider,
            selectedProvider = ProviderRoutingAvailability(
                providerName = "Selected provider",
                appRoutingProvided = true,
                xrayRoutingProvided = false,
            ),
        )

        assertNull(routing.appRouting)
        assertNotNull(routing.routing)
    }

    @Test
    fun `manual apply availability matches actual routing under each policy and selected provider`() {
        val subscription = SubscriptionEntity(name = "Import source", url = "https://example.com/sub")
            .withSubscriptionAppRouting(SubscriptionAppRouting(listOf("com.example.app"), SubscriptionAppRoutingMode.Direct))
            .withSubscriptionRouting(SubscriptionRouting(emptyList()))
        val availability = subscription.providerRoutingAvailability()
        for (policy in RoutingPolicyControl.entries) {
            for (appProvided in listOf(false, true)) {
                for (xrayProvided in listOf(false, true)) {
                    val selected = ProviderRoutingAvailability("Selected provider", appProvided, xrayProvided)
                    val manual = subscription.manualRoutingData(policy, selected)
                    assertEquals(
                        manual.appRouting != null || manual.routing != null,
                        availability.canApplyManually(policy, selected),
                    )
                }
            }
        }
    }

    @Test
    fun `missing routing stays unavailable and manual policy can apply provider-managed routing`() {
        val selected = ProviderRoutingAvailability("Selected provider", appRoutingProvided = true, xrayRoutingProvided = true)
        assertFalse(null.canApplyManually(RoutingPolicyControl.User, selected))
        assertFalse(ProviderRoutingAvailability("Empty", false, false).canApplyManually(RoutingPolicyControl.User, selected))
        assertTrue(ProviderRoutingAvailability("Source", true, false).canApplyManually(RoutingPolicyControl.User, selected))
        assertFalse(ProviderRoutingAvailability("Source", true, false).canApplyManually(RoutingPolicyControl.SubscriptionProvider, selected))
    }

    @Test
    fun `precomputed availability matches absent single-type and malformed provider routing`() {
        val empty = SubscriptionEntity(name = "Source", url = "https://example.com/sub")
        val appOnly = empty.withSubscriptionAppRouting(SubscriptionAppRouting(listOf("com.example.app"), SubscriptionAppRoutingMode.Direct))
        val rulesOnly = empty.withSubscriptionRouting(SubscriptionRouting(emptyList()))
        val malformed = empty.copy(providerRouting = "invalid json")
        for (subscription in listOf(empty, appOnly, rulesOnly, malformed)) {
            val availability = subscription.providerRoutingAvailability()
            for (policy in RoutingPolicyControl.entries) {
                val manual = subscription.manualRoutingData(policy, selectedProvider = null)
                assertEquals(
                    manual.appRouting != null || manual.routing != null,
                    availability.canApplyManually(policy, selectedProvider = null),
                )
            }
        }
    }
}
