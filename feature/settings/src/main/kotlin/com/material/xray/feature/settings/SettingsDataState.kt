package com.material.xray.feature.settings

import com.material.xray.core.common.di.ApplicationScope
import com.material.xray.core.data.repository.SettingsRepository
import com.material.xray.core.data.repository.SettingsSnapshot
import com.material.xray.core.data.repository.SubscriptionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.core.annotation.Singleton

/** Process-wide settings snapshot, loaded before the Settings screen is first composed. */
@Singleton
class SettingsDataState(
    settingsRepository: SettingsRepository,
    subscriptionRepository: SubscriptionRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    val data: StateFlow<SettingsSnapshot?> = settingsRepository.settingsSnapshot
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Whether the subscription behind the currently selected server demands the hardware ID.
     * While it does, sending is forced on without changing the user's saved preference.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedSubscriptionRequiresHardwareId: StateFlow<Boolean> = settingsRepository.lastServerId
        .flatMapLatest { serverId ->
            subscriptionRepository.observeRequiresHardwareIdForServer(serverId)
        }
        .map { it == true }
        .stateIn(scope, SharingStarted.Eagerly, false)
}
