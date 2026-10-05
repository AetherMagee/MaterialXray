package com.material.xray.feature.home

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.core.android.locale.forAppLanguage
import com.material.xray.core.android.locale.localizedString
import com.material.xray.core.common.connection.ConnectionEvent
import com.material.xray.core.common.connection.ConnectionStateCoordinator
import com.material.xray.core.data.parser.SubscriptionFetchException
import com.material.xray.core.data.repository.ProviderRoutingAvailability
import com.material.xray.core.data.repository.ServerRepository
import com.material.xray.core.data.repository.SettingsRepository
import com.material.xray.core.data.repository.toSubscriptionAppRouting
import com.material.xray.core.data.repository.toSubscriptionRouting
import com.material.xray.core.database.entity.ServerEntity
import com.material.xray.core.database.entity.SubscriptionEntity
import com.material.xray.core.model.AppUpdate
import com.material.xray.core.model.ConnectionState
import com.material.xray.core.model.PingMethod
import com.material.xray.core.model.RoutingPolicyControl
import com.material.xray.core.model.ServerConfig
import com.material.xray.core.model.SessionTrafficMetrics
import com.material.xray.core.model.SubscriptionAppRouting
import com.material.xray.core.model.SubscriptionRouting
import com.material.xray.core.model.SubscriptionUserAgentMode
import com.material.xray.core.model.maskedBalancerOutboundAddress
import com.material.xray.core.model.matchesBalancerOutbound
import com.material.xray.core.model.primaryBalancerTag
import com.material.xray.core.runtime.AlwaysOnVpnState
import com.material.xray.core.runtime.AppUpdateInstallProgress
import com.material.xray.core.runtime.ConnectionRuntimeManager
import com.material.xray.core.runtime.GeoDataManager
import com.material.xray.core.runtime.combinedGeoDataDownloadProgress
import com.material.xray.core.ui.R
import com.material.xray.service.XrayService
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.KoinViewModel

data class ServerListItem(
    val entity: ServerEntity,
    val endpointSummary: String,
    val latency: ServerLatencyState?,
)

data class ServerLatencyState(
    val latencyMs: Int,
    val method: PingMethod? = null,
    val tcpingLatencyMs: Int? = null,
    val httpingLatencyMs: Int? = null,
)

data class ActiveBalancerState(
    val serverId: Long,
    val servers: List<ActiveBalancerServer>,
    val isLoading: Boolean,
    val latencyMs: Long?,
)

data class ActiveBalancerServer(
    val outboundTag: String,
    val title: String,
    val latencyMs: Long?,
)

data class SubscriptionRoutingData(
    val appRouting: SubscriptionAppRouting?,
    val routing: SubscriptionRouting?,
)

internal fun SubscriptionEntity.manualRoutingData(
    policy: RoutingPolicyControl,
    selectedProvider: ProviderRoutingAvailability?,
) = SubscriptionRoutingData(
    appRouting = if (policy == RoutingPolicyControl.SubscriptionProvider && selectedProvider?.appRoutingProvided == true) null else toSubscriptionAppRouting(),
    routing = if (policy == RoutingPolicyControl.SubscriptionProvider && selectedProvider?.xrayRoutingProvided == true) null else toSubscriptionRouting(),
)

internal fun ProviderRoutingAvailability?.canApplyManually(
    policy: RoutingPolicyControl,
    selectedProvider: ProviderRoutingAvailability?,
): Boolean = this != null &&
    (
        appRoutingProvided &&
            (policy == RoutingPolicyControl.User || selectedProvider?.appRoutingProvided != true) ||
            xrayRoutingProvided &&
            (policy == RoutingPolicyControl.User || selectedProvider?.xrayRoutingProvided != true)
        )

internal data class HardwareIdConsentPrompt(val id: Long, val subscription: String)

sealed interface HomeUiEvent {
    data class Toast(val message: String) : HomeUiEvent
}

const val LATENCY_TESTING = Int.MIN_VALUE

@KoinViewModel
class HomeViewModel(
    private val context: Application,
    homeDataState: HomeDataState,
    private val settingsRepo: SettingsRepository,
    private val serverRepo: ServerRepository,
    private val appUpdates: HomeAppUpdates,
    private val serverController: HomeServerController,
    private val subscriptionOperations: SubscriptionOperations,
    private val latencyMeasurer: ServerLatencyMeasurer,
    private val connectionStateCoordinator: ConnectionStateCoordinator,
    private val connectionRuntimeManager: ConnectionRuntimeManager,
    alwaysOnVpnState: AlwaysOnVpnState,
    geoDataManager: GeoDataManager,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private var serverSelectionJob: Job? = null

    // Keyed by server and subscription id; like the rest of the latency state, only touched on the main thread.
    private val latencyProbeJobs = mutableMapOf<Long, Job>()
    private val latencySortJobs = mutableMapOf<Long, Job>()
    private val latencySemaphore = Semaphore(MAX_CONCURRENT_LATENCY_TESTS)

    val connectionState: StateFlow<ConnectionState> = connectionStateCoordinator.state
    internal val connectionProgress = connectionStateCoordinator.connectionProgress
    internal val geoDataDownloadFraction: StateFlow<Float?> = geoDataManager.downloadProgress
        .map { progress -> combinedGeoDataDownloadProgress(progress.values)?.fraction }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val alwaysOnVpn: StateFlow<Boolean> = alwaysOnVpnState.active
    val connectionEvents: Flow<ConnectionEvent> = connectionStateCoordinator.events
    private val _uiEvents = Channel<HomeUiEvent>(Channel.BUFFERED)
    val uiEvents: Flow<HomeUiEvent> = _uiEvents.receiveAsFlow()

    // The home data is shared process-wide and loaded eagerly on Activity startup, so on a typical
    // cold start every flow derived from it below starts out with the loaded snapshot as its
    // initial value instead of an empty placeholder, and the first composed frame is already
    // fully populated. `null` means the snapshot has not been built yet.
    private val homeData: StateFlow<HomeData?> = homeDataState.data

    val subscriptions: StateFlow<List<SubscriptionEntity>?> = homeData
        .map { it?.subscriptions }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), homeData.value?.subscriptions)

    val availableUpdate: StateFlow<AppUpdate?> = appUpdates.availableUpdate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val appUpdateInstallProgress: StateFlow<AppUpdateInstallProgress?> = appUpdates.installProgress

    private val allServers: StateFlow<List<ServerEntity>> = homeData
        .map { it?.servers.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), homeData.value?.servers.orEmpty())
    private val latencyByServerId = MutableStateFlow<Map<Long, ServerLatencyState>>(emptyMap())

    val serverItems: StateFlow<List<ServerListItem>> = combine(
        homeData,
        latencyByServerId,
    ) { data, latencies ->
        data?.serverItems.orEmpty().map { item ->
            latencies[item.entity.id]?.let { item.copy(latency = it) } ?: item
        }
    }
        // Overlaying the latency states copies every list item and reruns on each probe result;
        // keep that churn off the main dispatcher.
        .flowOn(defaultDispatcher)
        // Retain the prepared list while another tab is visible; returning must not replay it.
        .stateIn(viewModelScope, SharingStarted.Eagerly, homeData.value?.serverItems.orEmpty())

    val serversBySubscription: StateFlow<Map<Long, List<ServerListItem>>> = serverItems
        .map { items -> items.groupBy { it.entity.subscriptionId } }
        .flowOn(defaultDispatcher)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            serverItems.value.groupBy { it.entity.subscriptionId },
        )

    val selectedServerId: StateFlow<Long> = homeData
        .map { it?.selectedServerId ?: -1L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), homeData.value?.selectedServerId ?: -1L)

    val useRootService: StateFlow<Boolean> = settingsRepo.useRootService
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val showAdvancedOptions: StateFlow<Boolean> = settingsRepo.showAdvancedOptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val defaultPingMethod: StateFlow<PingMethod> = settingsRepo.defaultPingMethod
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PingMethod.default)
    val showBothLatencyResults: StateFlow<Boolean> = settingsRepo.showBothLatencyResults
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val routingPolicyControl: StateFlow<RoutingPolicyControl> = settingsRepo.routingPolicyControl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RoutingPolicyControl.default)
    internal val providerRoutingBySubscription: StateFlow<Map<Long, ProviderRoutingAvailability>> = homeData
        .map { it?.providerRoutingBySubscription.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, homeData.value?.providerRoutingBySubscription.orEmpty())
    internal val providerRoutingAvailability: StateFlow<ProviderRoutingAvailability?> = homeData
        .map { it?.selectedProviderRouting }
        .stateIn(viewModelScope, SharingStarted.Eagerly, homeData.value?.selectedProviderRouting)

    val selectedServer: StateFlow<ServerConfig?> = homeData
        .map { it?.selectedServer }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), homeData.value?.selectedServer)

    val activeBalancer: StateFlow<ActiveBalancerState?> = combine(
        connectionStateCoordinator.activeBalancerSelection,
        selectedServerId,
        selectedServer,
        allServers,
    ) { selection, selectedId, selectedConfig, servers ->
        if (selectedConfig?.primaryBalancerTag() == null) return@combine null
        val selectedEntity = servers.firstOrNull { it.id == selectedId } ?: return@combine null
        val peers = if (selection?.outbounds.isNullOrEmpty()) {
            emptyList()
        } else {
            servers.asSequence()
                .filter { it.id != selectedId && it.subscriptionId == selectedEntity.subscriptionId }
                .mapNotNull { entity ->
                    runCatching { entity.name to serverRepo.parseConfig(entity) }.getOrNull()
                }
                .toList()
        }
        ActiveBalancerState(
            serverId = selectedId,
            servers = selection?.outbounds.orEmpty().map { outbound ->
                val title = peers.firstOrNull { (_, config) ->
                    selectedConfig.matchesBalancerOutbound(outbound.outboundTag, config)
                }?.first
                    ?: selectedConfig.maskedBalancerOutboundAddress(outbound.outboundTag)
                    ?: outbound.outboundTag
                ActiveBalancerServer(outbound.outboundTag, title, outbound.latencyMs)
            },
            isLoading = selection == null,
            latencyMs = selection?.latencyMs,
        )
    }
        // Matching the balancer outbound parses server configs from the whole subscription.
        .flowOn(defaultDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /**
     * Live traffic counters for the connection banner. Subscribing here is what makes the service
     * poll Xray at all, so the grace period keeps a tab switch from restarting its loop.
     */
    val sessionTraffic: StateFlow<SessionTrafficMetrics?> = connectionStateCoordinator.sessionTraffic
        .stateIn(viewModelScope, liveReadingSharing(), null)

    /**
     * Round-trip time to whatever the tunnel is currently using, measured by the service against
     * the config it actually connected with. Subscribing here is what starts the probe, so nothing
     * is measured for the banner while it is off screen.
     */
    val activeServerPingMs: StateFlow<Int?> = connectionStateCoordinator.activePingMs
        .stateIn(viewModelScope, liveReadingSharing(), null)

    /**
     * Keeps the last reading on screen when the banner comes back, rather than dashing every cell
     * until fresh numbers arrive. The service primes its loop so a real reading lands within a
     * fraction of a second, which bounds how long a carried-over rate can be shown.
     */
    private fun liveReadingSharing() = SharingStarted.WhileSubscribed(LIVE_READING_GRACE_MILLIS)

    val refreshingSubscriptionIds = subscriptionOperations.refreshingSubscriptionIds
    private val _pendingSubscriptionRouting = MutableStateFlow<SubscriptionRoutingData?>(null)
    val pendingSubscriptionRouting: StateFlow<SubscriptionRoutingData?> = _pendingSubscriptionRouting.asStateFlow()

    /** Server the user picked while an edited active config is stored, pending their confirmation. */
    private val _pendingServerSelection = MutableStateFlow<Long?>(null)
    val pendingServerSelection: StateFlow<Long?> = _pendingServerSelection.asStateFlow()

    private val hardwareIdConsentMutex = Mutex()
    private var hardwareIdConsent: CompletableDeferred<Boolean>? = null
    private var hardwareIdConsentId = 0L
    private val _pendingHwidAddition = MutableStateFlow<HardwareIdConsentPrompt?>(null)
    internal val pendingHwidAddition: StateFlow<HardwareIdConsentPrompt?> = _pendingHwidAddition.asStateFlow()
    val showInstallPermissionRationale: StateFlow<Boolean> = appUpdates.installPermissionRationaleRequired

    fun connect() {
        val server = selectedServer.value ?: return
        serverController.connect(server)
    }

    fun disconnect() {
        XrayService.disconnect(context)
    }

    fun refreshTunnelInterfaceState() {
        viewModelScope.launch {
            connectionRuntimeManager.reconcileState()
        }
    }

    fun checkForAppUpdateIfDue() {
        viewModelScope.launch { appUpdates.checkIfDue() }
    }

    fun installAppUpdate(update: AppUpdate) {
        runAppUpdateStep { appUpdates.install(update) }
    }

    fun resumePendingAppUpdateInstall() {
        runAppUpdateStep { appUpdates.resumePendingInstall() }
    }

    fun confirmInstallPermissionRationale() {
        runAppUpdateStep { appUpdates.confirmInstallPermissionRationale() }
    }

    fun dismissInstallPermissionRationale() {
        appUpdates.dismissInstallPermissionRationale()
    }

    private fun runAppUpdateStep(step: suspend () -> Boolean) {
        viewModelScope.launch {
            if (!step()) {
                _uiEvents.send(HomeUiEvent.Toast(context.localizedString(R.string.home_app_update_install_failed)))
            }
        }
    }

    fun selectServer(serverId: Long) {
        serverSelectionJob?.cancel()
        serverSelectionJob = viewModelScope.launch {
            selectServerChecked(serverId)
        }
    }

    private suspend fun selectServerChecked(serverId: Long) {
        when (serverController.blocker(serverId)) {
            ServerSelectionBlocker.EditedActiveConfig -> _pendingServerSelection.value = serverId
            null -> serverController.apply(serverId)
        }
    }

    fun confirmHwidRequiredAddition(promptId: Long) {
        if (_pendingHwidAddition.value?.id == promptId) hardwareIdConsent?.complete(true)
    }

    fun dismissHwidRequiredAddition(promptId: Long) {
        if (_pendingHwidAddition.value?.id == promptId) hardwareIdConsent?.complete(false)
    }

    private suspend fun requestHardwareIdConsent(name: String, url: String): Boolean = hardwareIdConsentMutex.withLock {
        val answer = CompletableDeferred<Boolean>()
        hardwareIdConsent = answer
        val label = name.trim().ifBlank { runCatching { URI(url.trim()).host }.getOrNull() ?: url.trim() }
        _pendingHwidAddition.value = HardwareIdConsentPrompt(++hardwareIdConsentId, label)
        try {
            answer.await()
        } finally {
            _pendingHwidAddition.value = null
            hardwareIdConsent = null
        }
    }

    fun confirmDiscardEditedActiveConfig() {
        val serverId = _pendingServerSelection.value ?: return
        _pendingServerSelection.value = null
        serverSelectionJob?.cancel()
        serverSelectionJob = viewModelScope.launch {
            serverController.discardEditedActiveConfig()
            serverController.apply(serverId)
        }
    }

    fun dismissDiscardEditedActiveConfig() {
        _pendingServerSelection.value = null
    }

    fun addSubscription(
        name: String,
        url: String,
        preferJson: Boolean,
        allowInsecureUpdates: Boolean,
        userAgentMode: SubscriptionUserAgentMode,
        customUserAgent: String,
        customHeaders: String,
    ) {
        viewModelScope.launch {
            runSubscriptionOperation {
                subscriptionOperations.add(
                    name = name,
                    url = url,
                    preferJson = preferJson,
                    allowInsecureUpdates = allowInsecureUpdates,
                    userAgentMode = userAgentMode,
                    customUserAgent = customUserAgent,
                    customHeaders = customHeaders,
                    confirmHardwareId = { requestHardwareIdConsent(name, url) },
                )
            }
        }
    }

    fun addLink(link: String) {
        viewModelScope.launch {
            runSubscriptionOperation { subscriptionOperations.addLink(link) { requestHardwareIdConsent("", link) } }
        }
    }

    fun requestApplySubscriptionRouting(sub: SubscriptionEntity) {
        val routing = sub.manualRoutingData(
            policy = routingPolicyControl.value,
            selectedProvider = providerRoutingAvailability.value,
        )
        if (routing.appRouting == null && routing.routing == null) return
        _pendingSubscriptionRouting.value = routing
    }

    fun applyPendingSubscriptionRouting() {
        viewModelScope.launch {
            val data = _pendingSubscriptionRouting.value ?: return@launch
            subscriptionOperations.applyRouting(data)
            _pendingSubscriptionRouting.value = null
        }
    }

    fun dismissPendingSubscriptionRouting() {
        _pendingSubscriptionRouting.value = null
    }

    fun deleteSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch { subscriptionOperations.delete(sub) }
    }

    fun updateSubscription(
        sub: SubscriptionEntity,
        name: String,
        url: String,
        preferJson: Boolean,
        allowInsecureUpdates: Boolean,
        autoUpdateIntervalHours: Int,
        userAgentMode: SubscriptionUserAgentMode,
        customUserAgent: String,
        customHeaders: String,
    ) {
        viewModelScope.launch {
            runSubscriptionOperation {
                subscriptionOperations.update(
                    sub = sub,
                    name = name,
                    url = url,
                    preferJson = preferJson,
                    allowInsecureUpdates = allowInsecureUpdates,
                    autoUpdateIntervalHours = autoUpdateIntervalHours,
                    userAgentMode = userAgentMode,
                    customUserAgent = customUserAgent,
                    customHeaders = customHeaders,
                )
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            runSubscriptionOperation {
                val result = subscriptionOperations.refreshAll()
                reportBatchRefreshFailures(result.failures)
            }
        }
    }

    fun refreshSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch {
            runSubscriptionOperation { subscriptionOperations.refresh(sub) }
        }
    }

    fun setSubscriptionAutoUpdateInterval(subId: Long, intervalHours: Int) {
        viewModelScope.launch {
            subscriptionOperations.setAutoUpdateInterval(subId, intervalHours)
        }
    }

    fun setSubscriptionDescriptionHidden(subId: Long, hidden: Boolean) {
        viewModelScope.launch {
            subscriptionOperations.setDescriptionHidden(subId, hidden)
        }
    }

    fun reorderSubscriptions(subscriptionIds: List<Long>) {
        viewModelScope.launch {
            subscriptionOperations.reorder(subscriptionIds)
        }
    }

    fun setDefaultPingMethod(method: PingMethod) {
        viewModelScope.launch {
            settingsRepo.setDefaultPingMethod(method)
        }
    }

    fun setShowBothLatencyResults(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setShowBothLatencyResults(enabled)
        }
    }

    fun testLatency(server: ServerEntity) {
        startLatencyTests(listOf(server))
    }

    fun testSubscriptionLatencies(sub: SubscriptionEntity) {
        startLatencyTests(
            servers = allServers.value.filter { it.subscriptionId == sub.id },
            sortSubscriptionId = sub.id,
        )
    }

    fun testAllLatencies() {
        startLatencyTests(allServers.value)
    }

    /** Restarts the probes of [servers] only; probes of every other server keep running. */
    private fun startLatencyTests(servers: List<ServerEntity>, sortSubscriptionId: Long? = null) {
        val targetServers = servers.distinctBy { it.id }
        if (targetServers.isEmpty()) return
        val pingMethod = defaultPingMethod.value
        val pingMethods = if (showBothLatencyResults.value) {
            listOf(PingMethod.Tcping, PingMethod.Httping)
        } else {
            listOf(pingMethod)
        }

        latencyByServerId.update { current ->
            current + targetServers.associate { server ->
                server.id to latencyState(
                    primaryMethod = pingMethod,
                    latencyByMethod = pingMethods.associateWith { LATENCY_TESTING },
                )
            }
        }
        targetServers.forEach { server -> startLatencyProbe(server, pingMethod, pingMethods) }

        if (sortSubscriptionId != null) {
            latencySortJobs.remove(sortSubscriptionId)?.cancel()
            latencySortJobs[sortSubscriptionId] = viewModelScope.launch {
                if (settingsRepo.sortOutboundsByLatency.first()) sortWhileProbing(targetServers)
            }
        }
    }

    private fun startLatencyProbe(server: ServerEntity, primaryMethod: PingMethod, methods: List<PingMethod>) {
        latencyProbeJobs.remove(server.id)?.cancel()
        // Started only once registered, so a probe that finishes without suspending still unregisters itself.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val latency = latencySemaphore.withPermit { latencyMeasurer.measure(server, primaryMethod, methods) }
                latencyByServerId.update { it + (server.id to latency) }
            } finally {
                if (latencyProbeJobs[server.id] === coroutineContext.job) latencyProbeJobs.remove(server.id)
            }
        }
        latencyProbeJobs[server.id] = job
        job.start()
    }

    /** Re-sorts [servers] until none of them is being probed, whichever request started the probe. */
    private suspend fun sortWhileProbing(servers: List<ServerEntity>) {
        var persistedOrder = servers.sortedBy { it.sortOrder }.map { it.id }
        do {
            val probes = servers.mapNotNull { latencyProbeJobs[it.id] }
            withTimeoutOrNull(LATENCY_SORT_INTERVAL_MILLIS) { probes.joinAll() }
            persistedOrder = updateServerSortOrder(persistedOrder)
        } while (probes.isNotEmpty())
    }

    /** Sorting the last persisted order keeps servers that are being probed again where they were. */
    private suspend fun updateServerSortOrder(persistedOrder: List<Long>): List<Long> {
        val sortedOrder = sortedServerIdsByLatency(persistedOrder, latencyByServerId.value)
        val changes = changedServerSortOrders(persistedOrder, sortedOrder)
        if (changes.isNotEmpty()) serverRepo.updateSortOrders(changes)
        return sortedOrder
    }

    private suspend fun runSubscriptionOperation(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            _uiEvents.send(HomeUiEvent.Toast(subscriptionFailureMessage(error)))
        }
    }

    private suspend fun reportBatchRefreshFailures(failures: Map<Long, IOException>) {
        if (failures.isEmpty()) return

        val firstFailure = subscriptionFailureMessage(failures.values.first())
        val message = if (failures.size == 1) {
            firstFailure
        } else {
            context.forAppLanguage().resources.getQuantityString(
                R.plurals.home_subscription_refresh_batch_failed,
                failures.size,
                failures.size,
                firstFailure,
            )
        }
        _uiEvents.send(HomeUiEvent.Toast(message))
    }

    private fun subscriptionFailureMessage(error: IOException): String = when (error) {
        is SubscriptionFetchException -> when (error.reason) {
            SubscriptionFetchException.Reason.INVALID_URL -> context.localizedString(
                R.string.home_subscription_fetch_failed_invalid_url,
            )

            SubscriptionFetchException.Reason.HTTP_STATUS -> context.localizedString(
                R.string.home_subscription_fetch_failed_http,
                error.statusCode ?: 0,
            )

            SubscriptionFetchException.Reason.INSECURE_TRANSPORT -> context.localizedString(
                R.string.home_subscription_fetch_failed_insecure,
            )

            SubscriptionFetchException.Reason.EMPTY_RESPONSE -> context.localizedString(
                R.string.home_subscription_fetch_failed_empty,
                error.statusCode ?: 0,
            )

            SubscriptionFetchException.Reason.UNSUPPORTED_CONTENT -> context.localizedString(
                R.string.home_subscription_fetch_failed_unsupported,
                error.statusCode ?: 0,
            )
        }

        is SocketTimeoutException -> context.localizedString(R.string.home_subscription_fetch_failed_timeout)
        is UnknownHostException -> context.localizedString(R.string.home_subscription_fetch_failed_dns)
        is SSLException -> context.localizedString(R.string.home_subscription_fetch_failed_tls)
        is ConnectException -> context.localizedString(R.string.home_subscription_fetch_failed_connection)
        else -> context.localizedString(R.string.home_subscription_fetch_failed_network)
    }

    private companion object {
        const val MAX_CONCURRENT_LATENCY_TESTS = 25
        const val LATENCY_SORT_INTERVAL_MILLIS = 750L
        const val LIVE_READING_GRACE_MILLIS = 5_000L
    }
}

internal fun sortedServerIdsByLatency(
    serverIds: List<Long>,
    latencyByServerId: Map<Long, ServerLatencyState>,
): List<Long> = serverIds.sortedWith(
    compareBy<Long>(
        { serverId -> latencyByServerId[serverId]?.latencyMs?.let { it < 0 } ?: true },
        { serverId -> latencyByServerId[serverId]?.latencyMs?.takeIf { it >= 0 } ?: 0 },
    ),
)

internal fun changedServerSortOrders(current: List<Long>, sorted: List<Long>): Map<Long, Int> {
    if (current == sorted) return emptyMap()
    val currentPositions = current.withIndex().associate { (index, serverId) -> serverId to index }
    return sorted.mapIndexedNotNull { index, serverId ->
        if (currentPositions[serverId] == index) null else serverId to index
    }.toMap()
}

internal fun latencyState(
    primaryMethod: PingMethod,
    latencyByMethod: Map<PingMethod, Int>,
): ServerLatencyState = ServerLatencyState(
    latencyMs = latencyByMethod[primaryMethod] ?: -1,
    method = primaryMethod,
    tcpingLatencyMs = latencyByMethod[PingMethod.Tcping],
    httpingLatencyMs = latencyByMethod[PingMethod.Httping],
)
