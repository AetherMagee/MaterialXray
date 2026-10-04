package com.material.xray.core.data.repository

import com.material.xray.core.database.entity.AppBypassEntity
import com.material.xray.core.database.entity.AppRouteAssignment
import com.material.xray.core.database.entity.AppRouteMode
import com.material.xray.core.database.entity.toAppBypassEntity
import com.material.xray.core.model.SubscriptionAppRouting
import com.material.xray.core.model.SubscriptionAppRoutingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderAppRoutingMergeTest {
    private val routing = SubscriptionAppRouting(
        packageNames = listOf("com.provider.listed"),
        mode = SubscriptionAppRoutingMode.Direct,
    )

    @Test
    fun `manual rows for apps the provider does not claim survive a refresh`() {
        val userRow = row("com.user.edited", AppRouteMode.Direct, manual = true)
        val providerRow = row("com.provider.listed", AppRouteMode.Direct, manual = false)

        val merged = mergeProviderAssignments(
            current = listOf(userRow),
            providerAssignments = listOf(providerRow),
            routing = routing,
        )

        assertEquals(listOf(providerRow, userRow), merged)
    }

    @Test
    fun `provider replaces manual rows for apps it claims`() {
        val userRow = row("com.provider.listed", AppRouteMode.DefaultSelected, manual = true)
        val providerRow = row("com.provider.listed", AppRouteMode.Direct, manual = false)

        val merged = mergeProviderAssignments(
            current = listOf(userRow),
            providerAssignments = listOf(providerRow),
            routing = routing,
        )

        assertEquals(listOf(providerRow), merged)
    }

    @Test
    fun `stale provider rows are dropped`() {
        val staleProviderRow = row("com.previous.provider", AppRouteMode.Direct, manual = false)

        val merged = mergeProviderAssignments(
            current = listOf(staleProviderRow),
            providerAssignments = emptyList(),
            routing = routing,
        )

        assertEquals(emptyList<AppBypassEntity>(), merged)
    }

    @Test
    fun `inverted routing claims every unlisted app`() {
        val userRow = row("com.user.edited", AppRouteMode.Direct, manual = true)

        val merged = mergeProviderAssignments(
            current = listOf(userRow),
            providerAssignments = emptyList(),
            routing = routing.copy(inverted = true),
        )

        assertEquals(emptyList<AppBypassEntity>(), merged)
    }

    @Test
    fun `clearing provider routing keeps manual rows`() {
        val userRow = row("com.user.edited", AppRouteMode.Direct, manual = true)
        val providerRow = row("com.provider.listed", AppRouteMode.Direct, manual = false)

        val merged = mergeProviderAssignments(
            current = listOf(providerRow, userRow),
            providerAssignments = emptyList(),
            routing = null,
        )

        assertEquals(listOf(userRow), merged)
    }

    private fun row(packageName: String, mode: AppRouteMode, manual: Boolean): AppBypassEntity = AppRouteAssignment(mode).toAppBypassEntity(
        packageName = packageName,
        profileId = 0,
        uid = 10_000,
        manual = manual,
    )
}
