package com.material.xray.ui.navigation

import androidx.lifecycle.ViewModel
import com.material.xray.service.RoutingChangeManager
import com.material.xray.ui.settings.SettingsDataState
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
