package com.material.xray.data.repository

import com.material.xray.data.db.entity.SubscriptionEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRefreshPolicyTest {
    @Test
    fun invalidatedSubscriptionIsDueEvenWhenAutomaticUpdatesAreDisabled() {
        val subscription = SubscriptionEntity(
            name = "Sub",
            url = "https://example.com",
            lastUpdated = 0,
            autoUpdateIntervalHours = 0,
        )

        assertTrue(subscription.isDueForRefresh(nowMillis = 1_000))
    }

    @Test
    fun currentManualSubscriptionIsNotDue() {
        val subscription = SubscriptionEntity(
            name = "Sub",
            url = "https://example.com",
            lastUpdated = 1_000,
            autoUpdateIntervalHours = 0,
        )

        assertFalse(subscription.isDueForRefresh(nowMillis = 2_000))
    }

    @Test
    fun failedAutomaticRefreshWaitsAnHourBeforeRetrying() {
        val subscription = SubscriptionEntity(
            name = "Sub",
            url = "https://example.com",
            lastUpdated = 1_000,
            lastAutoRefreshFailureAt = 3_601_000,
            autoUpdateIntervalHours = 1,
        )

        assertFalse(subscription.isDueForRefresh(nowMillis = 3_601_000 + 59 * 60_000))
        assertTrue(subscription.isDueForRefresh(nowMillis = 3_601_000 + 60 * 60_000))
    }

    @Test
    fun failedInvalidatedSubscriptionIsBackedOffEvenWhenAutomaticUpdatesAreDisabled() {
        val subscription = SubscriptionEntity(
            name = "Sub",
            url = "https://example.com",
            lastUpdated = 0,
            lastAutoRefreshFailureAt = 1_000,
            autoUpdateIntervalHours = 0,
        )

        assertFalse(subscription.isDueForRefresh(nowMillis = 2_000))
        assertTrue(subscription.isDueForRefresh(nowMillis = 3_601_000))
    }

    @Test
    fun successfulRefreshClearsTheEffectOfAnEarlierFailure() {
        val subscription = SubscriptionEntity(
            name = "Sub",
            url = "https://example.com",
            lastUpdated = 10_000_000,
            lastAutoRefreshFailureAt = 9_000_000,
            autoUpdateIntervalHours = 1,
        )

        assertTrue(subscription.isDueForRefresh(nowMillis = 13_600_000))
    }
}
