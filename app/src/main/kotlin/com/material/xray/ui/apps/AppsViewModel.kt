package com.material.xray.ui.apps

import android.app.Application
import android.graphics.drawable.Drawable
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.R
import com.material.xray.core.app.AppInventory
import com.material.xray.core.app.appKey
import com.material.xray.core.locale.localizedString
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.db.entity.AppBypassEntity
import com.material.xray.data.db.entity.AppRouteAssignment
import com.material.xray.data.db.entity.AppRouteMode
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.db.entity.routeAssignment
import com.material.xray.data.db.entity.toAppBypassEntity
import com.material.xray.data.repository.ProviderRoutingAvailability
import com.material.xray.data.repository.ProviderRoutingCoordinator
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.providerRoutingAvailability
import com.material.xray.data.repository.toSubscriptionAppRouting
import com.material.xray.model.RootConnectionBackend
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.SubscriptionAppRouting
import com.material.xray.model.endpointSummary
import com.material.xray.model.proxyOutboundCount
import com.material.xray.service.AlwaysOnVpnState
import com.material.xray.service.PendingRoutingChange
import com.material.xray.service.RoutingChangeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.KoinViewModel

data class AppItem(
    val appKey: String,
    val packageName: String,
    val name: String,
    val uid: Int,
    val icon: Drawable?,
    val systemApp: Boolean,
    val profileId: Int,
    val workProfile: Boolean,
    val routeKey: String,
    val routeKind: AppRouteKind,
    val alwaysProxied: Boolean,
    val customRouted: Boolean,
    val routeTitle: AppRouteText,
    val routeDescription: AppRouteText,
)

data class AppLoadProgress(val processed: Int, val total: Int)

sealed interface AppRouteText {
    data class Resource(
        @param:StringRes val resourceId: Int,
        val arguments: List<Any> = emptyList(),
    ) : AppRouteText

    data class PluralResource(
        @param:PluralsRes val resourceId: Int,
        val quantity: Int,
        val arguments: List<Any> = emptyList(),
    ) : AppRouteText

    data class Raw(val value: String) : AppRouteText
}

enum class AppRouteKind {
    INHERIT,
    DEFAULT,
    DIRECT,
    BYPASS,
    SERVER,
}

data class AppRouteOption(
    val key: String,
    val title: AppRouteText,
    val description: AppRouteText,
    val kind: AppRouteKind,
    val serverId: Long? = null,
)

private data class AppListFilters(
    val searchQuery: String,
    val showSystemApps: Boolean,
    val showWorkProfileApps: Boolean,
)

@KoinViewModel
class AppsViewModel(
    private val context: Application,
    private val appBypassDao: AppBypassDao,
    private val subscriptionDao: SubscriptionDao,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    alwaysOnVpnState: AlwaysOnVpnState,
    private val providerRoutingCoordinator: ProviderRoutingCoordinator,
    private val routingChangeManager: RoutingChangeManager,
    private val appInventory: AppInventory,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _showSystemApps = MutableStateFlow(false)
    val showSystemApps: StateFlow<Boolean> = _showSystemApps

    private val _showWorkProfileApps = MutableStateFlow(true)
    val showWorkProfileApps: StateFlow<Boolean> = _showWorkProfileApps

    private val _isLoadingApps = MutableStateFlow(true)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps
    private val _appLoadProgress = MutableStateFlow<AppLoadProgress?>(null)
    val appLoadProgress: StateFlow<AppLoadProgress?> = _appLoadProgress
    private var loadAppsJob: Job? = null
    private var loadAppsRunId = 0L
    private var routingRefreshJob: Job? = null
    private val routeWriteMutex = Mutex()

    private val effectiveUseRootService = combine(
        settingsRepository.useRootService,
        alwaysOnVpnState.active,
    ) { useRootService, alwaysOnVpn -> useRootService && !alwaysOnVpn }
    val alwaysProxiedAvailable: StateFlow<Boolean> = effectiveUseRootService
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val appSpecificServerNoteShown: StateFlow<Boolean> = settingsRepository.appSpecificServerNoteShown
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val selectedSubscription: StateFlow<SubscriptionEntity?> = combine(
        settingsRepository.lastServerId,
        serverRepository.observeAll(),
        subscriptionDao.observeAll(),
    ) { selectedServerId, servers, subscriptions ->
        val subscriptionId = servers.firstOrNull { it.id == selectedServerId }?.subscriptionId
        subscriptions.firstOrNull { it.id == subscriptionId }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null,
    )
    private val selectedProviderRouting: StateFlow<ProviderRoutingAvailability?> = selectedSubscription
        .map { it?.providerRoutingAvailability() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val routingPolicyControl: StateFlow<RoutingPolicyControl> = combine(
        settingsRepository.routingPolicyControl,
        selectedProviderRouting,
    ) { policy, provider ->
        if (policy == RoutingPolicyControl.SubscriptionProvider && provider?.appRoutingProvided == true) {
            RoutingPolicyControl.SubscriptionProvider
        } else {
            RoutingPolicyControl.User
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RoutingPolicyControl.User)
    val automaticRoutingProviderName: StateFlow<String?> = selectedProviderRouting
        .map { it?.providerName }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The provider's app routing while it is in control, used to tell which apps it manages. */
    val providerAppRouting: StateFlow<SubscriptionAppRouting?> = combine(
        routingPolicyControl,
        selectedSubscription,
    ) { policy, subscription ->
        subscription?.toSubscriptionAppRouting()?.takeIf { policy == RoutingPolicyControl.SubscriptionProvider }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val bypassedApps = appBypassDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val installedApps = MutableStateFlow<List<AppItem>>(emptyList())
    private val _hasWorkProfileApps = MutableStateFlow(false)
    val hasWorkProfileApps: StateFlow<Boolean> = _hasWorkProfileApps

    val routeOptions: StateFlow<List<AppRouteOption>> = combine(
        serverRepository.observeAll(),
        settingsRepository.showAdvancedOptions,
        effectiveUseRootService,
        settingsRepository.rootConnectionBackend,
    ) { servers, showAdvancedOptions, useRootService, rootConnectionBackend ->
        if (!useRootService) {
            return@combine listOf(DEFAULT_ROUTE_OPTION, DIRECT_ROUTE_OPTION)
        }
        buildList {
            add(DEFAULT_ROUTE_OPTION)
            add(DIRECT_ROUTE_OPTION)
            if (showAdvancedOptions && rootConnectionBackend == RootConnectionBackend.Tun) add(BYPASS_ROUTE_OPTION)
            servers.forEach { server -> add(server.toRouteOption()) }
        }
    }
        // Building a route option parses each server's config JSON.
        .flowOn(defaultDispatcher)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            listOf(DEFAULT_ROUTE_OPTION, DIRECT_ROUTE_OPTION),
        )

    private val appListFilters = combine(
        _searchQuery,
        _showSystemApps,
        _showWorkProfileApps,
    ) { searchQuery, showSystemApps, showWorkProfileApps ->
        AppListFilters(
            searchQuery = searchQuery,
            showSystemApps = showSystemApps,
            showWorkProfileApps = showWorkProfileApps,
        )
    }

    val apps: StateFlow<List<AppItem>> = combine(
        installedApps,
        bypassedApps,
        appListFilters,
        routeOptions,
        effectiveUseRootService,
    ) { installed, assignments, filters, options, useRootService ->
        val assignmentByApp = assignments.associateBy { appKey(it.profileId, it.packageName) }
        val serverOptionsById = options
            .filter { it.kind == AppRouteKind.SERVER && it.serverId != null }
            .associateBy { requireNotNull(it.serverId) }
        installed
            .map { app ->
                val assignment = assignmentByApp[app.appKey]
                val option = app.resolveRouteOption(assignment, serverOptionsById, useRootService)
                app.copy(
                    routeKey = option.key,
                    routeKind = option.kind,
                    alwaysProxied = useRootService && assignment?.routeAssignment()?.alwaysProxied == true,
                    customRouted = option.kind != AppRouteKind.DEFAULT ||
                        (useRootService && assignment?.routeAssignment()?.alwaysProxied == true),
                    routeTitle = option.title,
                    routeDescription = option.description,
                )
            }
            .filter { filters.showSystemApps || !it.systemApp }
            .filter { filters.showWorkProfileApps || !it.workProfile }
            .filter {
                filters.searchQuery.isEmpty() ||
                    it.name.contains(filters.searchQuery, ignoreCase = true) ||
                    it.packageName.contains(filters.searchQuery, ignoreCase = true) ||
                    context.localizedString(
                        if (it.workProfile) R.string.apps_work_profile_label else R.string.apps_personal_profile_label,
                    ).contains(filters.searchQuery, ignoreCase = true)
            }
            .sortedWith(
                compareBy<AppItem> { !it.customRouted }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.profileId }
                    .thenBy { it.packageName },
            )
    }
        // Joining assignments over every installed app and re-sorting is linear in the size of
        // the app list; keep it off the main dispatcher.
        .flowOn(defaultDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refreshApps() {
        refreshProviderRouting()
        loadApps()
    }

    fun onVisible() {
        refreshProviderRouting()
        if (installedApps.value.isEmpty() && loadAppsJob?.isActive != true) loadApps()
    }

    fun onHidden() {
        loadAppsRunId++
        val wasLoading = loadAppsJob != null
        loadAppsJob?.cancel()
        loadAppsJob = null
        if (wasLoading) _isLoadingApps.value = false
        _appLoadProgress.value = null
    }

    private fun loadApps() {
        loadAppsJob?.cancel()
        val runId = ++loadAppsRunId
        loadAppsJob = viewModelScope.launch {
            _isLoadingApps.value = true
            _appLoadProgress.value = null
            try {
                val snapshot = appInventory.loadSnapshotWithProgress { processed, total ->
                    if (loadAppsRunId == runId) {
                        _appLoadProgress.value = AppLoadProgress(processed, total)
                    }
                }
                _hasWorkProfileApps.value = snapshot.profileIds.size > 1
                val apps = snapshot.apps
                    .filterNot { it.packageName == context.packageName }
                    .map { app ->
                        AppItem(
                            appKey = app.appKey,
                            packageName = app.packageName,
                            name = app.name,
                            uid = app.uid,
                            icon = app.icon,
                            systemApp = app.systemApp,
                            profileId = app.profileId,
                            workProfile = app.workProfile,
                            routeKey = DEFAULT_ROUTE_OPTION.key,
                            routeKind = DEFAULT_ROUTE_OPTION.kind,
                            alwaysProxied = false,
                            customRouted = false,
                            routeTitle = DEFAULT_ROUTE_OPTION.title,
                            routeDescription = DEFAULT_ROUTE_OPTION.description,
                        )
                    }
                    .sortedBy { it.name.lowercase() }
                installedApps.value = apps
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Package metadata is optional UI data; keep the previous snapshot on failure.
            } finally {
                if (loadAppsRunId == runId) {
                    loadAppsJob = null
                    _isLoadingApps.value = false
                    _appLoadProgress.value = null
                }
            }
        }
    }

    private fun refreshProviderRouting() {
        if (routingRefreshJob?.isActive == true) return
        routingRefreshJob = viewModelScope.launch {
            try {
                providerRoutingCoordinator.refreshSelectedServer()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Provider refresh is best-effort; the persisted routing remains usable.
            } finally {
                routingRefreshJob = null
            }
        }
    }

    fun setAppRoute(app: AppItem, option: AppRouteOption) {
        viewModelScope.launch {
            routeWriteMutex.withLock {
                val current = appBypassDao.getAll().firstOrNull { it.profileId == app.profileId && it.packageName == app.packageName }
                val latestAlwaysProxied = current?.routeAssignment()?.alwaysProxied ?: app.alwaysProxied
                val assignment = option.toRouteAssignment()?.copy(
                    alwaysProxied = latestAlwaysProxied && (option.kind == AppRouteKind.DEFAULT || option.kind == AppRouteKind.SERVER),
                ) ?: return@withLock
                appBypassDao.upsert(
                    assignment.toAppBypassEntity(
                        packageName = app.packageName,
                        profileId = app.profileId,
                        uid = app.uid,
                        manual = option.kind != AppRouteKind.DEFAULT || assignment.alwaysProxied,
                    ),
                )
                routingChangeManager.markPendingChanges(PendingRoutingChange.APP_ROUTING)
            }
        }
    }

    fun setAlwaysProxied(app: AppItem, enabled: Boolean) {
        if (app.routeKind != AppRouteKind.DEFAULT && app.routeKind != AppRouteKind.SERVER) return
        viewModelScope.launch {
            routeWriteMutex.withLock {
                val current = appBypassDao.getAll().firstOrNull { it.profileId == app.profileId && it.packageName == app.packageName }
                val route = current?.routeAssignment() ?: AppRouteAssignment(AppRouteMode.DefaultSelected)
                if (route.mode != AppRouteMode.DefaultSelected && route.mode != AppRouteMode.Server) return@withLock
                appBypassDao.upsert(
                    route.copy(alwaysProxied = enabled).toAppBypassEntity(
                        packageName = app.packageName,
                        profileId = app.profileId,
                        uid = app.uid,
                        manual = route.mode == AppRouteMode.Server || enabled,
                    ),
                )
                routingChangeManager.markPendingChanges(PendingRoutingChange.APP_ROUTING)
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setShowSystemApps(show: Boolean) {
        _showSystemApps.value = show
    }

    fun setShowWorkProfileApps(show: Boolean) {
        _showWorkProfileApps.value = show
    }

    fun setAppSpecificServerNoteShown() {
        viewModelScope.launch {
            settingsRepository.setAppSpecificServerNoteShown(true)
        }
    }

    fun switchToManualRouting() {
        viewModelScope.launch {
            settingsRepository.setRoutingPolicyControl(RoutingPolicyControl.User)
        }
    }

    fun bypassAllApps() {
        viewModelScope.launch {
            installedApps.value.forEach {
                appBypassDao.upsert(
                    AppRouteAssignment(AppRouteMode.Direct).toAppBypassEntity(
                        packageName = it.packageName,
                        profileId = it.profileId,
                        uid = it.uid,
                        manual = false,
                    ),
                )
            }
            routingChangeManager.markPendingChanges(PendingRoutingChange.APP_ROUTING)
        }
    }

    fun resetAllToDefault() {
        viewModelScope.launch {
            installedApps.value.forEach {
                appBypassDao.upsert(
                    AppRouteAssignment(AppRouteMode.DefaultSelected).toAppBypassEntity(
                        packageName = it.packageName,
                        profileId = it.profileId,
                        uid = it.uid,
                        manual = false,
                    ),
                )
            }
            routingChangeManager.markPendingChanges(PendingRoutingChange.APP_ROUTING)
        }
    }

    private fun AppItem.resolveRouteOption(
        assignment: AppBypassEntity?,
        serverOptionsById: Map<Long, AppRouteOption>,
        useRootService: Boolean,
    ): AppRouteOption {
        if (assignment == null) return DEFAULT_ROUTE_OPTION
        if (!useRootService) {
            return when (assignment.routeAssignment()) {
                AppRouteAssignment(AppRouteMode.Direct),
                AppRouteAssignment(AppRouteMode.Bypass),
                -> DIRECT_ROUTE_OPTION
                else -> DEFAULT_ROUTE_OPTION
            }
        }
        return when (val routeAssignment = assignment.routeAssignment()) {
            AppRouteAssignment(AppRouteMode.Direct) -> DIRECT_ROUTE_OPTION
            AppRouteAssignment(AppRouteMode.Bypass) -> BYPASS_ROUTE_OPTION
            AppRouteAssignment(AppRouteMode.DefaultOutbound) -> INHERIT_ROUTE_OPTION
            AppRouteAssignment(AppRouteMode.DefaultSelected) -> DEFAULT_ROUTE_OPTION
            else -> {
                val serverId = routeAssignment.serverId ?: return DEFAULT_ROUTE_OPTION
                serverOptionsById[serverId] ?: AppRouteOption(
                    key = serverRouteKey(serverId),
                    title = AppRouteText.Resource(R.string.apps_route_missing_server_title),
                    description = AppRouteText.Resource(R.string.apps_route_missing_server_description),
                    kind = AppRouteKind.SERVER,
                    serverId = serverId,
                )
            }
        }
    }

    private fun ServerEntity.toRouteOption(): AppRouteOption {
        val config = runCatching { serverRepository.parseConfig(this) }.getOrNull()
        val outboundCount = config?.proxyOutboundCount()
        val description = when {
            outboundCount != null -> AppRouteText.PluralResource(
                resourceId = R.plurals.apps_server_multiconnect_summary,
                quantity = outboundCount,
                arguments = listOf(outboundCount),
            )
            config != null -> AppRouteText.Raw(config.endpointSummary())
            else -> AppRouteText.Resource(
                R.string.apps_server_endpoint_unknown,
                listOf(protocol.lowercase(java.util.Locale.ROOT)),
            )
        }
        return AppRouteOption(
            key = serverRouteKey(id),
            title = AppRouteText.Raw(name),
            description = description,
            kind = AppRouteKind.SERVER,
            serverId = id,
        )
    }

    companion object {
        private const val INHERIT_ROUTE_KEY = "inherit"
        private const val DEFAULT_ROUTE_KEY = "default"
        private const val DIRECT_ROUTE_KEY = "direct"
        private const val BYPASS_ROUTE_KEY = "bypass"

        val INHERIT_ROUTE_OPTION = AppRouteOption(
            key = INHERIT_ROUTE_KEY,
            title = AppRouteText.Resource(R.string.apps_route_default_outbound_title),
            description = AppRouteText.Resource(R.string.apps_route_default_outbound_description),
            kind = AppRouteKind.INHERIT,
        )
        val DEFAULT_ROUTE_OPTION = AppRouteOption(
            key = DEFAULT_ROUTE_KEY,
            title = AppRouteText.Resource(R.string.apps_route_default_server_title),
            description = AppRouteText.Resource(R.string.apps_route_default_server_description),
            kind = AppRouteKind.DEFAULT,
        )
        val DIRECT_ROUTE_OPTION = AppRouteOption(
            key = DIRECT_ROUTE_KEY,
            title = AppRouteText.Resource(R.string.apps_route_not_proxied_title),
            description = AppRouteText.Resource(R.string.apps_route_not_proxied_description),
            kind = AppRouteKind.DIRECT,
        )
        val BYPASS_ROUTE_OPTION = AppRouteOption(
            key = BYPASS_ROUTE_KEY,
            title = AppRouteText.Resource(R.string.apps_route_bypass_tun_title),
            description = AppRouteText.Resource(R.string.apps_route_bypass_tun_description),
            kind = AppRouteKind.BYPASS,
        )

        fun serverRouteKey(serverId: Long): String = "server:$serverId"

        private fun AppRouteOption.toRouteAssignment(): AppRouteAssignment? = when (kind) {
            AppRouteKind.INHERIT -> AppRouteAssignment(AppRouteMode.DefaultOutbound)
            AppRouteKind.DEFAULT -> AppRouteAssignment(AppRouteMode.DefaultSelected)
            AppRouteKind.DIRECT -> AppRouteAssignment(AppRouteMode.Direct)
            AppRouteKind.BYPASS -> AppRouteAssignment(AppRouteMode.Bypass)
            AppRouteKind.SERVER -> serverId?.let { AppRouteAssignment(AppRouteMode.Server, it) }
        }
    }
}
