package com.material.xray.navigation

import androidx.lifecycle.ViewModel
import com.material.xray.core.runtime.RoutingChangeManager
import com.material.xray.feature.settings.SettingsDataState
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class MainNavigationViewModel(
    private val routingChangeManager: RoutingChangeManager,
    settingsDataState: SettingsDataState,
) : ViewModel() {
    val settings = settingsDataState.data

    fun onLeavingRoutingTab() {
        routingChangeManager.maybeReloadActiveConnection()
    }

    fun onAppBackgrounded() {
        routingChangeManager.maybeReloadActiveConnection()
    }
}
