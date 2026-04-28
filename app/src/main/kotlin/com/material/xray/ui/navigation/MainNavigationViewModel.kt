package com.material.xray.ui.navigation

import androidx.lifecycle.ViewModel
import com.material.xray.service.RoutingChangeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MainNavigationViewModel @Inject constructor(
    private val routingChangeManager: RoutingChangeManager,
) : ViewModel() {
    fun onLeavingRoutingTab() {
        routingChangeManager.maybeReloadActiveConnection()
    }

    fun onAppBackgrounded() {
        routingChangeManager.maybeReloadActiveConnection()
    }
}
