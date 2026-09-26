package com.material.xray.service

import com.material.xray.model.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TproxyStaleRuleRemovalTest {

    @Test
    fun `leftovers are cleaned while nothing is connected and no state file exists`() {
        assertTrue(shouldRemoveStaleTproxyRules(ConnectionState.Disconnected, runtimeStateFileExists = false))
    }

    @Test
    fun `a running connection keeps the rules that carry it`() {
        listOf(
            ConnectionState.Connecting,
            ConnectionState.Disconnecting,
            ConnectionState.ApplyingRoutingChanges,
            ConnectionState.UpdatingRoutingData,
        ).forEach { state ->
            assertFalse("state=$state", shouldRemoveStaleTproxyRules(state, runtimeStateFileExists = false))
        }
    }

    @Test
    fun `a recorded runtime state is left to its own teardown`() {
        assertFalse(shouldRemoveStaleTproxyRules(ConnectionState.Disconnected, runtimeStateFileExists = true))
    }
}
