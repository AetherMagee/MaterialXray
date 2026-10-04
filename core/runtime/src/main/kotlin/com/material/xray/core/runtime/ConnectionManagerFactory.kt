package com.material.xray.core.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
import com.material.xray.core.android.app.AppInventory
import com.material.xray.core.android.locale.localizedString
import com.material.xray.core.android.platform.LogcatAppLogger
import com.material.xray.core.android.xray.AndroidLocalSockets
import com.material.xray.core.android.xray.AndroidPlatformDns
import com.material.xray.core.common.connection.ConnectionStateCoordinator
import com.material.xray.core.common.log.LogBuffer
import com.material.xray.core.common.log.LogSource
import com.material.xray.core.connection.ActiveRoutingUpdater
import com.material.xray.core.connection.AndroidRootCertificateBundle
import com.material.xray.core.connection.AppRoutingPlanner
import com.material.xray.core.connection.ConnectionCleanup
import com.material.xray.core.connection.ConnectionDiagnostics
import com.material.xray.core.connection.ConnectionEnvironment
import com.material.xray.core.connection.ConnectionError
import com.material.xray.core.connection.ConnectionManager
import com.material.xray.core.connection.ConnectionManagerDependencies
import com.material.xray.core.connection.ConnectionRoutingData
import com.material.xray.core.connection.RootShellConnectionRuntime
import com.material.xray.core.connection.RootShellDiagnosticCommandRunner
import com.material.xray.core.connection.ServerAddressConnectionResolver
import com.material.xray.core.connection.StateFileRoutingStateStore
import com.material.xray.core.connection.TproxyManagerRoutingGateway
import com.material.xray.core.connection.TunManagerRoutingGateway
import com.material.xray.core.connection.XrayBinaryConnectionAdapter
import com.material.xray.core.connection.XrayCliRoutingUpdater
import com.material.xray.core.connection.XrayConnectionApiClientFactory
import com.material.xray.core.connection.routing.TproxyManager
import com.material.xray.core.connection.routing.TunManager
import com.material.xray.core.data.repository.ProviderGeoDataManager
import com.material.xray.core.data.repository.ServerRepository
import com.material.xray.core.database.dao.AppBypassDao
import com.material.xray.core.model.RoutingRule
import com.material.xray.core.network.loadX509Certificates
import com.material.xray.core.root.RootShell
import com.material.xray.core.telemetry.TelemetryReporter
import com.material.xray.core.ui.R
import com.material.xray.core.xray.ConfigGenerator
import com.material.xray.core.xray.GeoDataStatus
import com.material.xray.core.xray.OtherVpnDns
import com.material.xray.core.xray.ProviderGeoDataResolution
import com.material.xray.core.xray.ServerAddressResolver
import com.material.xray.core.xray.TproxyPortAllocator
import com.material.xray.core.xray.XRAY_API_LOOPBACK_ADDRESS
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.annotation.Factory

internal class AndroidConnectionEnvironment(
    private val context: Context,
) : ConnectionEnvironment {
    override val binDir: String
        get() = context.filesDir.resolve("bin").absolutePath
    override val appUid: Int
        get() = context.applicationInfo.uid
    override val processId: Int
        get() = android.os.Process.myPid()
    override val appInstallTime: Long
        get() = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

    override fun allocateLoopbackApiPort(): Int = ServerSocket(
        0,
        1,
        InetAddress.getByName(XRAY_API_LOOPBACK_ADDRESS),
    ).use { it.localPort }

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun describe(error: ConnectionError): String = when (error) {
        ConnectionError.Unknown -> localized(R.string.error_unknown)
        ConnectionError.VpnPermissionRequired -> localized(R.string.connection_error_vpn_permission_required)
        ConnectionError.MissingProcessId -> localized(R.string.connection_error_missing_process_id)
        ConnectionError.CleanupFailed -> localized(R.string.connection_error_cleanup_failed)
        ConnectionError.TunNameDetection -> localized(R.string.connection_error_tun_name_detection)
        ConnectionError.RootAccessDenied -> localized(R.string.connection_error_root_access_denied)
        ConnectionError.SecureXrayApi -> localized(R.string.connection_error_secure_xray_api)
        ConnectionError.XrayBinaryNotFound -> localized(R.string.connection_error_xray_binary_not_found)
        ConnectionError.PhysicalRouteNotFound -> localized(R.string.connection_error_physical_route_not_found)
        is ConnectionError.ServerAddressUnresolved -> localized(R.string.connection_error_server_address_unresolved, error.host)
        ConnectionError.TproxyHealthCheck -> localized(R.string.connection_error_tproxy_health_check)
        is ConnectionError.ApplyIpRouting -> localized(
            R.string.connection_error_apply_ip_routing,
            error.detail ?: localized(R.string.error_unknown),
        )
        is ConnectionError.XrayCrashed -> localized(R.string.connection_error_xray_crashed, error.reason)
        is ConnectionError.TunTimeout -> localized(R.string.connection_error_tun_timeout, error.tunName)
        ConnectionError.XrayApiNotReady -> localized(R.string.connection_error_xray_api_not_ready)
    }

    private fun localized(@StringRes resourceId: Int, vararg arguments: Any): String = context.localizedString(resourceId, *arguments)

    override fun otherVpnDns(): OtherVpnDns? {
        val (netId, linkProperties) = newestVpn() ?: return null
        return OtherVpnDns.of(
            netId = netId,
            servers = linkProperties.dnsServers.mapNotNull { it.hostAddress },
            searchDomains = linkProperties.domains,
        )
    }

    override fun otherVpnRoutes(): List<String> = newestVpn()?.second?.routes.orEmpty()
        // Before Android 13 the route type is hidden, and a VPN cannot exclude routes anyway.
        .filter { Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || it.type == RouteInfo.RTN_UNICAST }
        .mapNotNull { route -> route.destination.address.hostAddress?.let { "$it/${route.destination.prefixLength}" } }

    // Android runs one VPN per user. While it is being re-established the old network can linger
    // briefly, so the newest netId wins.
    @Suppress("DEPRECATION") // allNetworks is the only listing of every network, VPNs included.
    private fun newestVpn(): Pair<Int, LinkProperties>? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return connectivityManager.allNetworks.mapNotNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val linkProperties = connectivityManager.getLinkProperties(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true || linkProperties == null) {
                return@mapNotNull null
            }
            // Network's string form is its netId, which Android's socket marks carry.
            val netId = network.toString().toIntOrNull() ?: return@mapNotNull null
            netId to linkProperties
        }.maxByOrNull { it.first }
    }
}

internal class CleanupManagerConnectionAdapter(
    private val cleanupManager: CleanupManager,
) : ConnectionCleanup {
    override suspend fun ensureCleanState(fallbackTunName: String, preserveTproxyGuard: Boolean): Boolean = cleanupManager.ensureCleanState(fallbackTunName, preserveTproxyGuard)

    override suspend fun ensureKnownStateStopped(fallbackTunName: String, preserveTproxyGuard: Boolean): Boolean = cleanupManager.ensureKnownStateStopped(fallbackTunName, preserveTproxyGuard)

    override fun recordKnownCleanState(): Boolean = cleanupManager.recordKnownCleanState()

    override fun consumeKnownCleanState(): Boolean = cleanupManager.consumeKnownCleanState()
}

internal class GeoDataConnectionRoutingData(
    private val geoDataManager: GeoDataManager,
    private val providerGeoDataManager: ProviderGeoDataManager,
) : ConnectionRoutingData {
    override suspend fun needsRefresh(): Boolean = geoDataManager.needsRefresh()
    override suspend fun ensureReady(): GeoDataStatus = geoDataManager.ensureReady()
    override suspend fun resolveProviderRules(rules: List<RoutingRule>): ProviderGeoDataResolution = providerGeoDataManager.resolve(rules)
}

@Factory
class ConnectionManagerFactory(
    private val context: Context,
    private val shell: RootShell,
    private val geoDataManager: GeoDataManager,
    private val providerGeoDataManager: ProviderGeoDataManager,
    private val appBypassDao: AppBypassDao,
    private val serverRepository: ServerRepository,
    private val appInventory: AppInventory,
    private val stateCoordinator: ConnectionStateCoordinator,
    private val log: LogBuffer,
    private val telemetryReporter: TelemetryReporter,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val serverAddressResolver by lazy {
        ServerAddressResolver(AndroidPlatformDns(context), lastKnownFile = File(context.noBackupFilesDir, "server_addresses.json"))
    }
    private val rootCertificateBundle by lazy {
        AndroidRootCertificateBundle(
            refreshScope = CoroutineScope(SupervisorJob() + ioDispatcher),
            loadBundledCertificates = {
                context.resources.openRawResource(R.raw.mozilla_ca_bundle).use { input ->
                    loadX509Certificates(input).map { certificate -> certificate.encoded }
                }
            },
        )
    }

    internal fun create(): ConnectionManager {
        val environment = AndroidConnectionEnvironment(context)
        val xrayBinary = XrayBinaryConnectionAdapter(appXrayBinary(context))
        val runtimeEnvironment = AndroidXrayRuntimeEnvironment(context)
        val tunGateway = TunManagerRoutingGateway(TunManager(shell))
        val tproxyGateway = TproxyManagerRoutingGateway(
            manager = TproxyManager(shell, environment.appUid),
            portAllocator = TproxyPortAllocator(),
            appUid = environment.appUid,
        )
        val stateStore = StateFileRoutingStateStore(appStateFile(context))
        val rootProcess = XrayProcessSupervisor(
            environment = runtimeEnvironment,
            commandRunner = RootShellCommandRunner(shell),
            xrayBinary = xrayBinary,
            log = log,
        )
        val userProcess = UserXrayProcessSupervisor(
            environment = runtimeEnvironment,
            xrayBinary = xrayBinary,
        )
        val routingPlanBuilder = AppRoutingPlanner(
            appBypassDao = appBypassDao,
            serverRepository = serverRepository,
            appInventory = appInventory,
            serverAddressResolver = serverAddressResolver,
            log = log,
        )
        val dependencies = ConnectionManagerDependencies(
            environment = environment,
            rootRuntime = RootShellConnectionRuntime(shell),
            xrayBinary = xrayBinary,
            routingData = GeoDataConnectionRoutingData(geoDataManager, providerGeoDataManager),
            serverResolver = ServerAddressConnectionResolver(serverAddressResolver),
            tunGateway = tunGateway,
            tproxyGateway = tproxyGateway,
            cleanup = CleanupManagerConnectionAdapter(
                CleanupManager(context, shell) { message -> log.append(LogSource.APP, message) },
            ),
            stateStore = stateStore,
            rootProcess = rootProcess,
            userProcess = userProcess,
            diagnostics = ConnectionDiagnostics(RootShellDiagnosticCommandRunner(shell), log, environment.appUid),
            routingPlanBuilder = routingPlanBuilder,
            activeRouting = ActiveRoutingUpdater(
                appUidProvider = { environment.appUid },
                tunGateway = tunGateway,
                stateStore = stateStore,
                routingPlanBuilder = routingPlanBuilder,
                processProbe = rootProcess,
                log = log,
                elapsedRealtime = environment::elapsedRealtime,
                onProgressStarted = stateCoordinator::beginConnectionProgress,
                onProgressFinished = stateCoordinator::endConnectionProgress,
            ),
            apiClientFactory = XrayConnectionApiClientFactory(AndroidLocalSockets(), LogcatAppLogger()),
            xrayRoutingUpdater = XrayCliRoutingUpdater(
                userCommand = { xrayBinary.userCommand },
                binDir = environment.binDir,
            ),
            prepareCertificateBundle = {
                rootCertificateBundle.prepare(context.filesDir.resolve(XRAY_CERTIFICATE_BUNDLE_FILE))
            },
            startTelemetrySpan = telemetryReporter::startConnectionStep,
            recordTelemetryStepFailure = telemetryReporter::recordConnectionStepFailure,
        )
        return ConnectionManager(
            configGenerator = ConfigGenerator(),
            stateCoordinator = stateCoordinator,
            log = log,
            dependencies = dependencies,
        )
    }
}
