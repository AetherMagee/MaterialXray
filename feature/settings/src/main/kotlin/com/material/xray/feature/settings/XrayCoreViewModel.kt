package com.material.xray.feature.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.core.common.connection.ConnectionStateCoordinator
import com.material.xray.core.data.xraycore.XrayCoreException
import com.material.xray.core.data.xraycore.XrayCoreFailure
import com.material.xray.core.data.xraycore.XrayCoreManager
import com.material.xray.core.data.xraycore.XrayCoreRelease
import com.material.xray.core.data.xraycore.XrayCoreState
import com.material.xray.core.model.ConnectionState
import com.material.xray.service.XrayService
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

sealed interface XrayCoreReleasesState {
    data object Loading : XrayCoreReleasesState

    data class Loaded(val releases: List<XrayCoreRelease>) : XrayCoreReleasesState

    data class Failed(val failure: XrayCoreFailure) : XrayCoreReleasesState
}

@KoinViewModel
class XrayCoreViewModel(
    private val context: Application,
    private val manager: XrayCoreManager,
    private val connectionStateCoordinator: ConnectionStateCoordinator,
) : ViewModel() {
    private val _releases = MutableStateFlow<XrayCoreReleasesState>(XrayCoreReleasesState.Loading)
    private var releasesRequested = false

    val state: StateFlow<XrayCoreState> = manager.state
    val releases: StateFlow<XrayCoreReleasesState> = _releases.asStateFlow()
    val canDownload: Boolean = manager.canDownload

    /** Lists releases once per view model, since GitHub allows few anonymous requests an hour. */
    fun loadReleasesIfNeeded() {
        if (canDownload && !releasesRequested) loadReleases()
    }

    fun loadReleases() {
        releasesRequested = true
        _releases.value = XrayCoreReleasesState.Loading
        viewModelScope.launch {
            _releases.value = try {
                XrayCoreReleasesState.Loaded(manager.releases())
            } catch (error: XrayCoreException) {
                XrayCoreReleasesState.Failed(error.failure)
            }
        }
    }

    fun install(release: XrayCoreRelease) = manager.install(release)

    fun installFromFile(uri: Uri) = manager.installFromFile {
        context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open $uri")
    }

    fun cancel() = manager.cancel()

    fun clearResult() = manager.clearResult()

    /** Selects installed core [id], or the bundled core when null, and restarts a running connection on it. */
    fun select(id: String?) = viewModelScope.launch {
        if (id == state.value.selectedId) return@launch
        manager.select(id)
        val connection = connectionStateCoordinator.state.value
        if (connection is ConnectionState.Connected || connection is ConnectionState.ApplyingRoutingChanges) {
            XrayService.reload(context)
        }
    }

    fun delete(id: String) = viewModelScope.launch { manager.delete(id) }
}
