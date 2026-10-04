package com.material.xray.feature.xraycore

import android.content.Context
import com.material.xray.core.common.connection.ConnectionStateCoordinator
import com.material.xray.core.model.ConnectionState
import com.material.xray.core.xraycore.XrayCoreManager
import com.material.xray.service.XrayService
import org.koin.core.annotation.Singleton

/** Switches cores for the page and the update worker, restarting a running connection on the new one. */
@Singleton
class XrayCoreSwitcher(
    private val context: Context,
    private val manager: XrayCoreManager,
    private val connectionStateCoordinator: ConnectionStateCoordinator,
) {
    /** Selects installed core [id], or the bundled core when null. */
    suspend fun switchTo(id: String?) {
        if (id == manager.state.value.selectedId) return
        manager.select(id)
        val connection = connectionStateCoordinator.state.value
        if (connection is ConnectionState.Connected || connection is ConnectionState.ApplyingRoutingChanges) {
            XrayService.reload(context)
        }
    }
}
