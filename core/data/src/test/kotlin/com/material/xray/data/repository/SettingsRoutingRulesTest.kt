package com.material.xray.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRoutingRulesTest {
    @Test
    fun `deleted defaults stay absent without a serialized custom rule list`() {
        val rules = defaultRoutingRules(
            stateOverrides = emptyMap(),
            deletedDefaultRuleIds = setOf("ru-direct", "block-ads"),
        )

        assertEquals(emptyList<Any>(), rules)
    }
}
