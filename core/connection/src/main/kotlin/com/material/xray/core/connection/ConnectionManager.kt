package com.material.xray.core.connection

import com.material.xray.core.common.connection.ConnectionStateCoordinator
import com.material.xray.core.common.log.LogBuffer
import com.material.xray.core.common.log.LogSource
import com.material.xray.core.connection.routing.TproxyTrafficPlan
import com.material.xray.core.connection.routing.TunManager
import com.material.xray.core.model.ConnectionProgress
import com.material.xray.core.model.ConnectionState
import com.material.xray.core.model.OtherVpnMode
import com.material.xray.core.model.RootConnectionBackend
import com.material.xray.core.model.ServerConfig
import com.material.xray.core.model.XrayRuntimeSettings
import com.material.xray.core.telemetry.ConnectionTelemetryStep
import com.material.xray.core.xray.ConfigGenerator
import com.material.xray.core.xray.XrayApiEndpoint
import com.material.xray.core.xray.XrayState
import com.material.xray.core.xray.XraySysStats
import com.material.xray.core.xray.parseXrayApiEndpoint
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Suppress("LargeClass")
class ConnectionManager(
    private val configGenerator: ConfigGenerator,
    private val stateCoordinator: ConnectionStateCoordinator,
    private val log: LogBuffer,
    dependencies: ConnectionManagerDependencies,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : XrayHealthProbe {
    private val environment = dependencies.environment
    private val rootRuntime = dependencies.rootRuntime
    private val xrayBinary = dependencies.xrayBinary
    private val routingData = dependencies.routingData
    private val serverResolver = dependencies.serverResolver
    private val tunGateway = dependencies.tunGateway
    private val tproxyGateway = dependencies.tproxyGateway
    private val cleanup = dependencies.cleanup
    private val stateStore = dependencies.stateStore
    private val processSupervisor = dependencies.rootProcess
    private val userProcessSupervisor = dependencies.userProcess
    private val diagnostics = dependencies.diagnostics
    private val appRoutingPlanner = dependencies.routingPlanBuilder
    private val activeRouting = dependencies.activeRouting
    private val prepareCertificateBundle = dependencies.prepareCertificateBundle
    private val stepExecutor = ConnectionStepExecutor(
        elapsedRealtime = environment::elapsedRealtime,
        log = { message -> log.append(LogSource.APP, message) },
        onProgressStarted = stateCoordinator::beginConnectionProgress,
        onProgressFinished = stateCoordinator::endConnectionProgress,
        onTraceStarted = dependencies.startTelemetrySpan,
        onTelemetryStepFailed = dependencies.recordTelemetryStepFailure,
    )

    private val rootStrategy: XrayRuntimeStrategy = RootXrayRuntimeStrategy(
        processSupervisor = processSupervisor,
        rootRuntime = rootRuntime,
        cleanup = cleanup,
        xrayBinary = xrayBinary,
    )
    private val vpnServiceStrategy: XrayRuntimeStrategy = VpnServiceXrayRuntimeStrategy(
        processSupervisor = userProcessSupervisor,
        stateStore = stateStore,
        xrayBinary = xrayBinary,
    )

    private val apiClients = XrayApiClients(dependencies.apiClientFactory)

    private val configWriter = XrayConfigWriter(
        configGenerator = configGenerator,
        environment = environment,
        xrayBinary = xrayBinary,
        routingData = routingData,
        routingUpdater = dependencies.xrayRoutingUpdater,
        log = log,
        stepExecutor = stepExecutor,
        defaultDispatcher = defaultDispatcher,
    )

    private val tproxyRouting = ActiveTproxyRouting(
        gateway = tproxyGateway,
        stateStore = stateStore,
        appRoutingPlanner = appRoutingPlanner,
        environment = environment,
        log = log,
        stepExecutor = stepExecutor,
        isProcessAlive = ::isProcessAlive,
        isRootProcessAlive = processSupervisor::isAlive,
    )

    @Volatile private var runtimeState: XrayRuntimeState = XrayRuntimeState.Inactive

    @Volatile private var transitionGuardInstalled = false

    @Volatile private var preserveGuardOnFailure = false

    @Volatile private var rootRuntimeKnownClean = false

    private var rootRoutingKnownCleanForConnect = false

    // Root is the only runtime that installs routing outside the process; the rootless runtime
    // gets it from Android's VpnService.
    val isUsingRootRuntime: Boolean
        get() = runtimeState.strategy?.managesSystemRouting == true

    @Suppress("CyclomaticComplexMethod")
    suspend fun connect(
        server: ServerConfig,
        runtimeSettings: XrayRuntimeSettings,
        establishVpnInterface: suspend () -> Int? = { null },
        syntheticDnsAddress: String? = null,
        transitionState: ConnectionState = ConnectionState.Connecting,
        preparation: ConnectionPreparation = ConnectionPreparation.Full,
    ) {
        stateCoordinator.startConnection(transitionState)
        configWriter.active = null
        rootRoutingKnownCleanForConnect = false
        val connectStartedAt = environment.elapsedRealtime()
        val routeTable = runtimeSettings.routeTable
        log.clear(LogSource.XRAY)
        log.append(LogSource.APP, "Connecting to ${server.name} (${server.address}:${server.port})")
        val strategy = strategyFor(useRootService = runtimeSettings.useRootService)
        val managesSystemRouting = strategy.managesSystemRouting
        val rootBackend = effectiveRootBackend(managesSystemRouting, runtimeSettings.rootConnectionBackend)
        runtimeState = XrayRuntimeState.Starting(strategy)

        try {
            val tunName = prepareRuntime(
                strategy = strategy,
                preparation = preparation,
                configuredTunName = runtimeSettings.tunName,
                rootBackend = rootBackend,
            ) ?: return
            if (prepareXrayBinary(strategy, preparation) == null) return
            prepareRoutingData(preparation, transitionState)
            val effectiveRuntimeSettings = runtimeSettings.copy(
                tunName = tunName,
                routingRules = configWriter.resolveProviderRoutingRules(runtimeSettings.routingRules),
            )

            val physicalRouteResult = detectPhysicalRoute(managesSystemRouting, tunName)
            if (!physicalRouteResult.success) return

            val xrayServer = resolveServer(server, runtimeSettings.allowIpv6) ?: return

            val appRoutingPlan = executeStep(
                ConnectionStep(
                    "Build app routing plan",
                    ConnectionProgress.ConfiguringRouting,
                    telemetryStep = ConnectionTelemetryStep.BuildAppRouting,
                ) {
                    appRoutingPlanner.build(
                        baseRouteTable = routeTable,
                        includeProxyRoutes = managesSystemRouting,
                        includeTunRoutes = managesSystemRouting,
                        includeDefaultSelectedRoute = rootBackend != RootConnectionBackend.Tproxy ||
                            !runtimeSettings.usesProxyAsRoutingDefault(),
                        defaultProxyServer = xrayServer,
                        allowIpv6 = runtimeSettings.allowIpv6,
                    )
                },
            )
            val tproxyPreparation = prepareTproxyPlan(
                rootBackend,
                appRoutingPlan,
                runtimeSettings,
                physicalRouteResult.route,
            )
            val tproxyPlan = tproxyPreparation.plan
            if (hasConfiguredAppRouting(appRoutingPlan)) {
                logAppRoutingPlan(appRoutingPlan)
            }

            val xrayApiEndpoint = strategy.nextApiEndpoint(environment)
            if (!prepareXrayApiAccess(xrayApiEndpoint)) return
            executeStep(
                ConnectionStep(
                    "Create Xray control API clients",
                    ConnectionProgress.PreparingCore,
                    telemetryStep = ConnectionTelemetryStep.CreateApiClients,
                ) {
                    apiClients.replace(xrayApiEndpoint)
                },
            )
            val setup = ConnectionSetup(
                serverName = server.name,
                tunName = tunName,
                runtimeSettings = runtimeSettings,
                managesSystemRouting = managesSystemRouting,
                rootBackend = rootBackend,
                appRoutingPlan = appRoutingPlan,
                physicalRoute = physicalRouteResult.route,
                xrayApiEndpoint = xrayApiEndpoint,
                tproxyPlan = tproxyPlan,
            )
            if (!prepareTproxyInterception(setup)) return

            val generatedConfig = configWriter.write(
                xrayServer,
                effectiveRuntimeSettings,
                managesSystemRouting,
                rootBackend,
                appRoutingPlan,
                physicalRouteResult.route,
                xrayApiEndpoint,
                tproxyPlan,
                syntheticDnsAddress,
            )
            val vpnInterface = if (managesSystemRouting) {
                null
            } else {
                executeStep(
                    ConnectionStep(
                        "Establish Android VPN interface",
                        ConnectionProgress.ConfiguringTunnel,
                        telemetryStep = ConnectionTelemetryStep.VpnInterface,
                        isSuccessful = { it != null },
                        action = establishVpnInterface,
                    ),
                ) ?: run {
                    val error = stateCoordinator.state.value as? ConnectionState.Error
                    fail(
                        error?.message ?: environment.describe(ConnectionError.VpnPermissionRequired),
                        retryable = error?.retryable ?: false,
                    )
                    return
                }
            }
            val pid = startXrayProcess(
                strategy = strategy,
                vpnInterface = vpnInterface,
                primaryGid = environment.appUid.takeIf { rootBackend == RootConnectionBackend.Tproxy },
                // Matches XrayConfigWriter.write, which gives the core a TUN inbound exactly when there is
                // no TPROXY plan to give it TPROXY inbounds instead.
                tun = RootTunDevice(tunName, runtimeSettings.tunMtu).takeIf { managesSystemRouting && tproxyPlan == null },
            )

            if (pid <= 0) {
                fail(environment.describe(ConnectionError.MissingProcessId))
                return
            }
            runtimeState = XrayRuntimeState.Active(
                strategy = strategy,
                pid = pid,
                tunName = tunName,
                apiEndpoint = xrayApiEndpoint,
                physicalRoute = physicalRouteResult.route,
            )
            log.append(LogSource.APP, "xray running with PID $pid")
            writeConnectionStateFile(setup, pid, ipRulesApplied = false)

            if (!finishRuntimeSetup(setup, pid)) return

            configWriter.active = generatedConfig

            finishSuccessfulConnection(setup, pid, connectStartedAt)
        } catch (error: CancellationException) {
            withContext(NonCancellable) { cleanCancelledConnectionAttempt() }
            throw error
        } catch (error: IOException) {
            fail(error.message ?: environment.describe(ConnectionError.Unknown))
        } catch (error: SecurityException) {
            fail(error.message ?: environment.describe(ConnectionError.Unknown))
        } catch (error: IllegalArgumentException) {
            fail(error.message ?: environment.describe(ConnectionError.Unknown))
        } catch (error: IllegalStateException) {
            fail(error.message ?: environment.describe(ConnectionError.Unknown))
        }
    }

    private fun hasConfiguredAppRouting(plan: AppRoutingPlan): Boolean = plan.proxyRoutes.isNotEmpty() ||
        plan.directUids.isNotEmpty()

    private suspend fun prepareRuntime(
        strategy: XrayRuntimeStrategy,
        preparation: ConnectionPreparation,
        configuredTunName: String,
        rootBackend: RootConnectionBackend,
    ): String? {
        val customTunName = configuredTunName.trim()
        val persistedKnownCleanState = strategy.managesSystemRouting && cleanup.consumeKnownCleanState()
        val canReuseKnownCleanState = rootRuntimeKnownClean || persistedKnownCleanState
        rootRoutingKnownCleanForConnect = strategy.managesSystemRouting && canReuseKnownCleanState && !transitionGuardInstalled
        if (strategy.managesSystemRouting) rootRuntimeKnownClean = false
        if (preparation.cleansPreviousState && strategy.managesSystemRouting) {
            if (canReuseKnownCleanState && !transitionGuardInstalled) {
                log.append(LogSource.APP, "Previous root runtime is already clean")
            } else {
                log.append(LogSource.APP, "Cleaning up previous state...")
                val cleaned = executeStep(
                    ConnectionStep(
                        "Cleanup",
                        ConnectionProgress.PreparingRuntime,
                        telemetryStep = ConnectionTelemetryStep.CleanupPreviousRuntime,
                        isSuccessful = { it },
                        action = {
                            cleanup.ensureCleanState(
                                fallbackTunName = customTunName.ifEmpty { LEGACY_DEFAULT_TUN_NAME },
                                preserveTproxyGuard = transitionGuardInstalled && preserveGuardOnFailure,
                            )
                        },
                    ),
                )
                if (!cleaned) {
                    fail(environment.describe(ConnectionError.CleanupFailed), cleanState = false)
                    return null
                }
            }
        }

        val ready = if (strategy.managesSystemRouting) {
            prepareRootRuntime(preparation)
        } else {
            prepareVpnServiceRuntime()
        }
        if (!ready) return null

        val tunName = if (
            strategy.managesSystemRouting &&
            rootBackend == RootConnectionBackend.Tun &&
            customTunName.isEmpty()
        ) {
            executeStep(
                ConnectionStep(
                    "TUN interface name detection",
                    ConnectionProgress.PreparingRuntime,
                    telemetryStep = ConnectionTelemetryStep.DetectTunInterface,
                    isSuccessful = { it != null },
                    action = tunGateway::findAvailableWlanName,
                ),
            )?.also { selectedName ->
                log.append(LogSource.APP, "Selected available TUN interface name $selectedName")
            } ?: run {
                fail(
                    environment.describe(ConnectionError.TunNameDetection),
                    cleanState = false,
                )
                return null
            }
        } else if (strategy.managesSystemRouting && rootBackend == RootConnectionBackend.Tproxy) {
            TPROXY_INTERFACE_LABEL
        } else {
            customTunName
        }

        executeStep(
            ConnectionStep(
                "Prepare Xray log file",
                ConnectionProgress.PreparingRuntime,
                telemetryStep = ConnectionTelemetryStep.PrepareLog,
            ) {
                strategy.prepareLogFile()
            },
        )
        executeStep(
            ConnectionStep(
                "Prepare CA certificate bundle",
                ConnectionProgress.PreparingRuntime,
                telemetryStep = ConnectionTelemetryStep.PrepareCertificateBundle,
                action = prepareCertificateBundle,
            ),
        )
        return tunName
    }

    private suspend fun prepareTproxyPlan(
        rootBackend: RootConnectionBackend,
        appRoutingPlan: AppRoutingPlan,
        runtimeSettings: XrayRuntimeSettings,
        physicalRoute: TunManager.PhysicalRoute?,
    ): TproxyPlanPreparation {
        if (rootBackend != RootConnectionBackend.Tproxy) return TproxyPlanPreparation(null)
        return TproxyPlanPreparation(
            tproxyGateway.createPlan(
                appRoutingPlan = appRoutingPlan,
                routeTable = runtimeSettings.routeTable,
                allowIpv6 = runtimeSettings.allowIpv6,
                tetherUpstreamInterface = physicalRoute?.dev?.takeIf { runtimeSettings.tunnelTetheredClients },
                bypassLan = runtimeSettings.bypassLan,
                otherVpnMode = runtimeSettings.otherVpnMode,
                otherVpnRoutes = environment.otherVpnRoutes(),
            ),
        )
    }

    private suspend fun prepareTproxyInterception(setup: ConnectionSetup): Boolean {
        val tproxyPlan = setup.tproxyPlan ?: return true
        writeConnectionStateFile(setup, pid = -1, ipRulesApplied = false)
        val guardResult = executeStep(
            ConnectionStep(
                "TPROXY startup guard",
                ConnectionProgress.ConfiguringRouting,
                telemetryStep = ConnectionTelemetryStep.InstallTproxyGuard,
                isSuccessful = { it.success },
                action = { tproxyGateway.installGuard(tproxyPlan) },
            ),
        )
        if (!guardResult.success) {
            failRouting(guardResult)
            return false
        }
        transitionGuardInstalled = true
        return true
    }

    private suspend fun prepareRootRuntime(preparation: ConnectionPreparation): Boolean {
        log.append(LogSource.APP, "Requesting root access...")
        val rootGranted = executeStep(
            ConnectionStep(
                "Root shell setup",
                ConnectionProgress.PreparingRuntime,
                telemetryStep = ConnectionTelemetryStep.RootAccess,
                isSuccessful = { it },
                action = rootRuntime::open,
            ),
        )
        if (!rootGranted) {
            fail(environment.describe(ConnectionError.RootAccessDenied))
            return false
        }
        log.append(
            LogSource.APP,
            "Root access granted (namespace=${rootRuntime.networkNamespaceName()})",
        )
        if (preparation.reusesStaticRuntime) {
            log.append(LogSource.APP, "Runtime exemption check skipped for fast reconnect")
        } else {
            processSupervisor.ensureNativeRuntimeExemptions()
        }
        return true
    }

    private suspend fun prepareVpnServiceRuntime(): Boolean {
        log.append(LogSource.APP, "Using Android VpnService")
        cleanOrphanedVpnServiceRuntime()
        userProcessSupervisor.stop()
        return true
    }

    private suspend fun prepareRootApiAccess(endpoint: XrayApiEndpoint): Boolean {
        if (endpoint !is XrayApiEndpoint.LoopbackTcp) return true
        if (rootRuntime.protectLoopbackApi(endpoint.port, environment.appUid)) return true
        fail(environment.describe(ConnectionError.SecureXrayApi))
        return false
    }

    private suspend fun prepareXrayApiAccess(endpoint: XrayApiEndpoint): Boolean = if (endpoint is XrayApiEndpoint.LoopbackTcp) {
        executeStep(
            ConnectionStep(
                "Xray API firewall setup",
                ConnectionProgress.PreparingCore,
                telemetryStep = ConnectionTelemetryStep.PrepareApiAccess,
                isSuccessful = { it },
                action = { prepareRootApiAccess(endpoint) },
            ),
        )
    } else {
        prepareRootApiAccess(endpoint)
    }

    private suspend fun cleanOrphanedVpnServiceRuntime() {
        val staleState = stateStore.read()
            ?.takeIf { it.physicalInterface == VPN_SERVICE_INTERFACE_LABEL }
            ?: return
        userProcessSupervisor.stopOrphan(staleState.xrayPid)
        stateStore.delete()
    }

    private suspend fun prepareXrayBinary(strategy: XrayRuntimeStrategy, preparation: ConnectionPreparation): String? {
        val verifyAvailable = !preparation.reusesStaticRuntime
        if (verifyAvailable) {
            log.append(LogSource.APP, "Checking xray binary...")
        } else {
            log.append(LogSource.APP, "xray binary check skipped for fast reconnect")
        }
        val activeBinaryPath = if (verifyAvailable) {
            executeStep(
                ConnectionStep(
                    "xray binary setup",
                    ConnectionProgress.PreparingCore,
                    telemetryStep = ConnectionTelemetryStep.PrepareCoreBinary,
                    isSuccessful = { it != null },
                    action = { strategy.prepareBinary(verifyAvailable = true) },
                ),
            )
        } else {
            strategy.prepareBinary(verifyAvailable = false)
        }
        if (activeBinaryPath == null) {
            fail(environment.describe(ConnectionError.XrayBinaryNotFound))
            return null
        }
        log.append(LogSource.APP, "xray binary ready at $activeBinaryPath")
        return activeBinaryPath
    }

    private suspend fun prepareRoutingData(preparation: ConnectionPreparation, transitionState: ConnectionState) {
        if (preparation.reusesStaticRuntime) {
            log.append(LogSource.APP, "Routing data check skipped for fast reconnect")
            return
        }

        log.append(LogSource.APP, "Checking routing data...")
        if (routingData.needsRefresh()) {
            stateCoordinator.markUpdatingRoutingData()
            log.append(LogSource.APP, "Updating routing data...")
        }
        val geoDataStatus = executeStep(
            ConnectionStep(
                "Routing data setup",
                ConnectionProgress.UpdatingRoutingData,
                telemetryStep = ConnectionTelemetryStep.PrepareRoutingData,
                action = routingData::ensureReady,
            ),
        )
        stateCoordinator.startConnection(transitionState)
        if (geoDataStatus.downloaded) {
            log.append(
                LogSource.APP,
                "Routing data updated (geoip=${geoDataStatus.geoipUrl}, geosite=${geoDataStatus.geositeUrl})",
            )
        } else {
            log.append(LogSource.APP, "Routing data already up to date")
        }
    }

    private suspend fun detectPhysicalRoute(managesSystemRouting: Boolean, tunName: String): PhysicalRouteResult {
        if (!managesSystemRouting) return PhysicalRouteResult(success = true, route = null)

        val route = executeStep(
            ConnectionStep(
                "Physical route detection",
                progress = ConnectionProgress.PreparingRuntime,
                telemetryStep = ConnectionTelemetryStep.DetectPhysicalRoute,
                retryable = true,
                maxRetries = CONNECTION_STEP_MAX_RETRIES,
                retryDelayMs = CONNECTION_STEP_RETRY_DELAY_MS,
                isSuccessful = { it != null },
                reported = false,
                action = { tunGateway.detectPhysicalRoute(tunName) },
            ),
        )
        if (route == null) {
            fail(environment.describe(ConnectionError.PhysicalRouteNotFound))
            return PhysicalRouteResult(success = false, route = null)
        }
        log.append(
            LogSource.APP,
            "Physical bypass route: dev=${route.dev}" +
                route.gateway?.let { " via=$it" }.orEmpty() +
                route.table?.let { " table=$it" }.orEmpty(),
        )
        return PhysicalRouteResult(success = true, route = route)
    }

    /**
     * Looks [server] up while the running core can still carry the lookup. When another app's VPN
     * covers this app, Android hands the lookup to that VPN's resolver, whose traffic the reconnect
     * guard drops, so the reconnect relies on the resolver caching this answer.
     */
    suspend fun warmServerAddresses(server: ServerConfig) {
        if (!isUsingRootRuntime) return
        // Caching happens before the IPv6 filter, so allowing it here warms every address family.
        serverResolver.resolve(server, allowIpv6 = true)
    }

    private suspend fun resolveServer(server: ServerConfig, allowIpv6: Boolean): ServerConfig? {
        val resolvedServer = executeStep(
            ConnectionStep(
                "Server address resolution",
                ConnectionProgress.ResolvingEntryServer,
                telemetryStep = ConnectionTelemetryStep.ResolveServer,
                retryable = true,
                maxRetries = CONNECTION_STEP_MAX_RETRIES,
                retryDelayMs = CONNECTION_STEP_RETRY_DELAY_MS,
                isSuccessful = { !it.attempted || it.selectedAddress != null },
                action = { serverResolver.resolve(server, allowIpv6) },
            ),
        )
        if (resolvedServer.attempted && resolvedServer.selectedAddress == null) {
            val unresolvedHost = resolvedServer.unresolvedHosts.firstOrNull() ?: server.address
            fail(environment.describe(ConnectionError.ServerAddressUnresolved(unresolvedHost)))
            return null
        }
        if (resolvedServer.server.bootstrapDnsHosts.isNotEmpty()) {
            log.append(
                LogSource.APP,
                "Resolved " + resolvedServer.server.bootstrapDnsHosts.entries.joinToString("; ") { (host, addresses) ->
                    "$host to [${addresses.joinToString()}]"
                },
            )
        } else if (resolvedServer.selectedAddress != null) {
            log.append(
                LogSource.APP,
                "Resolved ${server.address} to ${resolvedServer.selectedAddress} (${resolvedServer.candidates.size} candidates)",
            )
        }
        return resolvedServer.server
    }

    /**
     * Whether another app's VPN changed the private DNS zone the running config routes to it. Xray
     * cannot reload DNS settings, so the caller has to reconnect to pick the change up.
     */
    fun otherVpnDnsChanged(): Boolean = configWriter.otherVpnDnsChanged()

    private fun logAppRoutingPlan(appRoutingPlan: AppRoutingPlan) {
        log.append(
            LogSource.APP,
            "App routing: ${appRoutingPlan.proxyRoutes.sumOf { route ->
                appRoutingPlan.tunRoutes.firstOrNull { it.index == route.routeIndex }?.uids?.size ?: 0
            }} apps assigned to ${appRoutingPlan.proxyRoutes.size} proxy route(s), ${appRoutingPlan.directUids.size} apps direct",
        )
    }

    private suspend fun startXrayProcess(
        strategy: XrayRuntimeStrategy,
        vpnInterface: Int?,
        primaryGid: Int? = null,
        tun: RootTunDevice? = null,
    ): Int {
        log.append(LogSource.APP, "Starting xray process...")
        return executeStep(
            ConnectionStep(
                "xray process launch",
                ConnectionProgress.StartingCore,
                telemetryStep = ConnectionTelemetryStep.LaunchCore,
                isSuccessful = { it > 0 },
                action = {
                    strategy.startProcess(
                        binDir = environment.binDir,
                        vpnInterfaceFd = vpnInterface,
                        primaryGid = primaryGid,
                        tun = tun,
                    )
                },
            ),
        )
    }

    private suspend fun writeConnectionStateFile(setup: ConnectionSetup, pid: Int, ipRulesApplied: Boolean) {
        val transitionGuard = if (transitionGuardInstalled && preserveGuardOnFailure) {
            stateStore.read()?.let { it.tproxy ?: it.transitionGuard }
        } else {
            null
        }
        stateStore.write(
            XrayState(
                appInstallTime = environment.appInstallTime,
                xrayPid = pid,
                xrayApiPort = (setup.xrayApiEndpoint as? XrayApiEndpoint.LoopbackTcp)?.port,
                tunName = setup.tunName,
                serverName = setup.serverName,
                ipRulesApplied = ipRulesApplied,
                fwmark = setup.fwmark,
                routeMark = setup.routeMark,
                routeTable = setup.routeTable,
                bypassTable = setup.bypassTable,
                appProxyServerIds = setup.appRoutingPlan.proxyServerIds,
                physicalInterface = setup.physicalRoute?.dev ?: VPN_SERVICE_INTERFACE_LABEL,
                physicalGateway = setup.physicalRoute?.gateway,
                physicalTable = setup.physicalRoute?.table,
                rootConnectionBackend = setup.rootBackend,
                tproxy = setup.tproxyPlan?.runtimeState,
                transitionGuard = transitionGuard,
                ipv6Enabled = setup.runtimeSettings.allowIpv6,
            ),
        )
    }

    private suspend fun waitForRootTun(
        managesSystemRouting: Boolean,
        tunName: String,
        appRouteCount: Int,
        allowIpv6: Boolean,
        pid: Int,
    ): Boolean {
        if (!managesSystemRouting) return true

        log.append(LogSource.APP, "Waiting for TUN interface '$tunName'...")
        val tunSetup = executeStep(
            ConnectionStep(
                "TUN setup",
                ConnectionProgress.ConfiguringTunnel,
                telemetryStep = ConnectionTelemetryStep.ConfigureTun,
                isSuccessful = { it.success },
                action = {
                    tunGateway.configureTun(
                        tunName = tunName,
                        appRouteCount = appRouteCount,
                        allowIpv6 = allowIpv6,
                        processId = pid,
                    ) { isProcessAlive(pid) }
                },
            ),
        )
        if (!tunSetup.success) {
            handleTunSetupFailure(tunSetup, tunName, pid, diagnosticsStage = "tun")
            return false
        }
        log.append(LogSource.APP, "TUN interface $tunName is up")
        return true
    }

    private suspend fun finishRuntimeSetup(setup: ConnectionSetup, pid: Int): Boolean {
        val tproxyPlan = setup.tproxyPlan
        if (setup.rootBackend == RootConnectionBackend.Tproxy && tproxyPlan != null) {
            return coroutineScope {
                val apiReadiness = async { measureXrayApiReadiness(pid) }
                val routingResult = executeStep(
                    ConnectionStep(
                        "TPROXY routing setup",
                        ConnectionProgress.ConfiguringRouting,
                        telemetryStep = ConnectionTelemetryStep.ActivateTproxy,
                        isSuccessful = { it.success },
                        action = { tproxyGateway.activate(tproxyPlan) },
                    ),
                )
                if (!routingResult.success) {
                    apiReadiness.cancel()
                    diagnostics.logTproxyDiagnostics("tproxy-activation-failure", tproxyPlan.runtimeState, pid)
                    failRouting(routingResult)
                    return@coroutineScope false
                }
                if (!finishXrayApiReadiness(apiReadiness.await())) return@coroutineScope false
                val verification = executeStep(
                    ConnectionStep(
                        "TPROXY routing verification",
                        ConnectionProgress.ConfiguringRouting,
                        telemetryStep = ConnectionTelemetryStep.VerifyTproxy,
                        isSuccessful = { it.success },
                        action = { tproxyGateway.verifyActivation(tproxyPlan.runtimeState) },
                    ),
                )
                if (!verification.success) {
                    diagnostics.logTproxyDiagnostics("tproxy-health-failure", tproxyPlan.runtimeState, pid)
                    log.append(LogSource.APP, "ERROR: ${verification.error}")
                    fail(environment.describe(ConnectionError.TproxyHealthCheck))
                    return@coroutineScope false
                }
                tproxyRouting.markAudited()
                if (tproxyPlan.runtimeState.otherVpnMode == OtherVpnMode.TunnelInTunnel) {
                    tproxyRouting.syncOtherVpnRules(tproxyPlan.runtimeState)
                }
                if (!ensureProcessAliveAfterSetup(pid)) return@coroutineScope false
                tproxyPlan.runtimeState.otherVpnRoutes.takeIf { it.isNotEmpty() }?.let { routes ->
                    log.append(LogSource.APP, "Left to the other VPN: ${routes.joinToString()}")
                }
                tproxyPlan.runtimeState.standDownRoutes.takeIf { it.isNotEmpty() }?.let { routes ->
                    log.append(LogSource.APP, "Standing down for the other VPN: ${routes.joinToString()}")
                }
                log.append(LogSource.APP, "TPROXY routing applied")
                finishTransitionGuard()
            }
        }
        if (!setup.managesSystemRouting) return waitForXrayApiReady(pid) && finishTransitionGuard()
        val settings = setup.runtimeSettings
        return coroutineScope {
            val apiReadiness = async { measureXrayApiReadiness(pid) }
            val routingReady = waitForRootTun(
                managesSystemRouting = true,
                tunName = setup.tunName,
                appRouteCount = setup.appRoutingPlan.tunRoutes.size,
                allowIpv6 = settings.allowIpv6,
                pid = pid,
            ) &&
                applyRootRouting(
                    managesSystemRouting = true,
                    tunName = setup.tunName,
                    fwmark = setup.fwmark,
                    routeTable = setup.routeTable,
                    bypassTable = setup.bypassTable,
                    physicalRoute = setup.physicalRoute,
                    allowIpv6 = settings.allowIpv6,
                    tunnelTetheredClients = settings.tunnelTetheredClients,
                    bypassLan = settings.bypassLan,
                    appRoutingPlan = setup.appRoutingPlan,
                )
            if (!routingReady) {
                apiReadiness.cancel()
                return@coroutineScope false
            }
            finishXrayApiReadiness(apiReadiness.await()) &&
                ensureProcessAliveAfterSetup(pid) &&
                finishTransitionGuard()
        }
    }

    private suspend fun finishTransitionGuard(): Boolean {
        if (!transitionGuardInstalled) return true
        val removed = executeStep(
            ConnectionStep(
                "TPROXY transition guard removal",
                ConnectionProgress.ConfiguringRouting,
                telemetryStep = ConnectionTelemetryStep.RemoveTproxyGuard,
                isSuccessful = { it },
                action = tproxyGateway::removeGuard,
            ),
        )
        if (!removed) {
            fail(environment.describe(ConnectionError.CleanupFailed))
            return false
        }
        transitionGuardInstalled = false
        preserveGuardOnFailure = false
        stateStore.read()?.let { state ->
            if (state.transitionGuard != null) stateStore.write(state.copy(transitionGuard = null))
        }
        return true
    }

    private suspend fun failRouting(result: TunManager.RoutingResult) {
        fail(
            environment.describe(ConnectionError.ApplyIpRouting(result.error)),
        )
    }

    private suspend fun handleTunSetupFailure(
        tunSetup: TunManager.TunSetupResult,
        tunName: String,
        pid: Int,
        diagnosticsStage: String,
    ) {
        val stage = if (tunSetup.processExited) "$diagnosticsStage-exit" else "$diagnosticsStage-failure"
        diagnostics.logNamespaceDiagnostics(stage = stage, tunName = tunName, xrayPid = pid)
        if (tunSetup.processExited) {
            fail(environment.describe(ConnectionError.XrayCrashed(readCrashReason())))
        } else {
            fail(
                tunSetup.error
                    ?: environment.describe(ConnectionError.TunTimeout(tunName)),
            )
        }
    }

    private suspend fun applyRootRouting(
        managesSystemRouting: Boolean,
        tunName: String,
        fwmark: Int,
        routeTable: Int,
        bypassTable: Int,
        physicalRoute: TunManager.PhysicalRoute?,
        allowIpv6: Boolean,
        tunnelTetheredClients: Boolean,
        bypassLan: Boolean,
        appRoutingPlan: AppRoutingPlan,
    ): Boolean {
        if (!managesSystemRouting) return true

        val bypassUids = runtimeBypassUids(appRoutingPlan.directUids)
        log.append(
            LogSource.APP,
            "Applying IP routing (tunTable=$routeTable, bypassTable=$bypassTable, fwmark=$fwmark, ${bypassUids.size} apps direct, ${appRoutingPlan.tunRoutes.size} app proxy route(s))...",
        )
        val routingResult = executeStep(
            ConnectionStep(
                "IP routing setup",
                ConnectionProgress.ConfiguringRouting,
                telemetryStep = ConnectionTelemetryStep.ApplyRootRouting,
                isSuccessful = { it.success },
                action = {
                    tunGateway.applyRouting(
                        tunName = tunName,
                        fwmark = fwmark,
                        routeTable = routeTable,
                        bypassTable = bypassTable,
                        physicalRoute = requireNotNull(physicalRoute),
                        allowIpv6 = allowIpv6,
                        bypassUids = bypassUids,
                        appTunRoutes = appRoutingPlan.tunRoutes,
                        managedAppRouteCount = appRoutingPlan.tunRoutes.size,
                        routeProfileIds = appRoutingPlan.routeProfileIds,
                        tunnelTetheredClients = tunnelTetheredClients,
                        bypassLan = bypassLan,
                        cleanExistingState = !rootRoutingKnownCleanForConnect,
                    )
                },
            ),
        )
        if (!routingResult.success) {
            fail(
                environment.describe(ConnectionError.ApplyIpRouting(routingResult.error)),
            )
            return false
        }
        log.append(LogSource.APP, "IP routing applied")
        return true
    }

    private suspend fun waitForXrayApiReady(pid: Int): Boolean = finishXrayApiReadiness(measureXrayApiReadiness(pid))

    private suspend fun measureXrayApiReadiness(pid: Int): XrayApiReadiness = executeStep(
        ConnectionStep(
            "Xray API readiness",
            ConnectionProgress.WaitingForCore,
            telemetryStep = ConnectionTelemetryStep.WaitForApi,
            isSuccessful = { it == XrayApiReadiness.Ready },
            action = { probeXrayApiReadiness(pid) },
        ),
    )

    private suspend fun probeXrayApiReadiness(pid: Int): XrayApiReadiness {
        val deadline = environment.elapsedRealtime() + XRAY_API_READY_TIMEOUT_MS
        do {
            if (readXraySysStats() != null) return XrayApiReadiness.Ready
            if (!isProcessAlive(pid)) return XrayApiReadiness.ProcessExited
            val remainingMs = deadline - environment.elapsedRealtime()
            if (remainingMs <= 0) break
            delay(minOf(XRAY_API_READY_RETRY_DELAY_MS, remainingMs))
        } while (true)
        return XrayApiReadiness.TimedOut
    }

    private suspend fun finishXrayApiReadiness(readiness: XrayApiReadiness): Boolean = when (readiness) {
        XrayApiReadiness.Ready -> true
        XrayApiReadiness.ProcessExited -> {
            fail(environment.describe(ConnectionError.XrayCrashed(readCrashReason())))
            false
        }
        XrayApiReadiness.TimedOut -> {
            fail(environment.describe(ConnectionError.XrayApiNotReady))
            false
        }
    }

    private suspend fun ensureProcessAliveAfterSetup(pid: Int): Boolean {
        if (isProcessAlive(pid)) return true
        fail(environment.describe(ConnectionError.XrayCrashed(readCrashReason())))
        return false
    }

    private suspend fun finishSuccessfulConnection(setup: ConnectionSetup, pid: Int, connectStartedAt: Long) {
        writeConnectionStateFile(setup, pid, ipRulesApplied = setup.managesSystemRouting)

        val physicalRoute = setup.physicalRoute
        log.append(LogSource.APP, "Connected to ${setup.serverName}")
        log.append(
            LogSource.APP,
            "Connection setup finished in ${environment.elapsedRealtime() - connectStartedAt} ms",
        )
        stateCoordinator.markConnected(
            ConnectionState.Connected(
                serverName = setup.serverName,
                corePid = pid,
                tunName = setup.tunName,
                physicalInterface = physicalRoute?.dev ?: VPN_SERVICE_INTERFACE_LABEL,
                physicalGateway = physicalRoute?.gateway,
                physicalTable = physicalRoute?.table,
            ),
        )
    }

    /** What a connection attempt has settled on by the time it hands off to the core. */
    private data class ConnectionSetup(
        val serverName: String,
        val tunName: String,
        val runtimeSettings: XrayRuntimeSettings,
        val managesSystemRouting: Boolean,
        val rootBackend: RootConnectionBackend,
        val appRoutingPlan: AppRoutingPlan,
        val physicalRoute: TunManager.PhysicalRoute?,
        val xrayApiEndpoint: XrayApiEndpoint,
        val tproxyPlan: TproxyTrafficPlan?,
    ) {
        val fwmark: Int get() = runtimeSettings.fwmark
        val routeTable: Int get() = runtimeSettings.routeTable
        val routeMark: Int get() = routeTable
        val bypassTable: Int get() = routeTable + 1
    }

    private data class PhysicalRouteResult(
        val success: Boolean,
        val route: TunManager.PhysicalRoute?,
    )

    private enum class XrayApiReadiness { Ready, ProcessExited, TimedOut }

    suspend fun localAddressesChanged(backend: RootConnectionBackend): Boolean = if (backend == RootConnectionBackend.Tproxy) tproxyGateway.localAddressesChanged() else tunGateway.localAddressesChanged()

    suspend fun refreshTetherAddresses(): Boolean = tproxyRouting.refreshTetherAddresses()

    suspend fun isTetherIngressActive(): Boolean = tproxyRouting.isTetherIngressActive()

    suspend fun applyAppRoutingChanges(
        connectedState: ConnectionState.Connected,
        runtimeSettings: XrayRuntimeSettings,
    ): Boolean = executeStep(
        ConnectionStep(
            label = "Fast app routing update",
            progress = ConnectionProgress.UpdatingAppRouting,
            isSuccessful = { it },
            action = { applyAppRoutingChangesOnce(connectedState, runtimeSettings) },
        ),
    )

    suspend fun applyXrayRoutingChanges(
        connectedState: ConnectionState.Connected,
        runtimeSettings: XrayRuntimeSettings,
    ): Boolean = executeStep(
        ConnectionStep(
            label = "Live Xray routing update",
            progress = ConnectionProgress.ConfiguringRouting,
            isSuccessful = { it },
            action = { applyXrayRoutingChangesOnce(connectedState, runtimeSettings) },
        ),
    )

    private suspend fun applyXrayRoutingChangesOnce(
        connectedState: ConnectionState.Connected,
        runtimeSettings: XrayRuntimeSettings,
    ): Boolean {
        val activeRuntime = runtimeState as? XrayRuntimeState.Active ?: return false
        if (activeRuntime.pid != connectedState.corePid || !isProcessAlive(connectedState.corePid)) return false
        return configWriter.applyRoutingChanges(activeRuntime.apiEndpoint, runtimeSettings)
    }

    private suspend fun applyAppRoutingChangesOnce(
        connectedState: ConnectionState.Connected,
        runtimeSettings: XrayRuntimeSettings,
    ): Boolean {
        if (runtimeSettings.rootConnectionBackend != RootConnectionBackend.Tproxy) {
            return activeRouting.applyAppRoutingChanges(
                connectedState = connectedState,
                tunName = connectedState.tunName,
                fwmark = runtimeSettings.fwmark,
                routeTable = runtimeSettings.routeTable,
                allowIpv6 = runtimeSettings.allowIpv6,
                tunnelTetheredClients = runtimeSettings.tunnelTetheredClients,
                bypassLan = runtimeSettings.bypassLan,
            )
        }
        return tproxyRouting.applyAppRoutingChanges(connectedState, runtimeSettings)
    }

    suspend fun followOtherVpnRouting(connectedState: ConnectionState.Connected, runtimeSettings: XrayRuntimeSettings) = tproxyRouting.followOtherVpnRouting(connectedState, runtimeSettings)

    suspend fun updatePhysicalBypassRoute(
        connectedState: ConnectionState.Connected,
        physicalRoute: TunManager.PhysicalRoute,
        runtimeSettings: XrayRuntimeSettings,
    ): PhysicalRouteUpdateResult = executeStep(
        ConnectionStep(
            label = "Update active physical route",
            progress = ConnectionProgress.UpdatingNetworkRoute,
            isSuccessful = { it is PhysicalRouteUpdateResult.Applied },
            action = { updatePhysicalBypassRouteOnce(connectedState, physicalRoute, runtimeSettings) },
        ),
    )

    private suspend fun updatePhysicalBypassRouteOnce(
        connectedState: ConnectionState.Connected,
        physicalRoute: TunManager.PhysicalRoute,
        runtimeSettings: XrayRuntimeSettings,
    ): PhysicalRouteUpdateResult {
        val persistedState = stateStore.read()
        if (persistedState?.rootConnectionBackend == RootConnectionBackend.Tproxy) {
            return tproxyRouting.updatePhysicalRoute(connectedState, physicalRoute, runtimeSettings, persistedState)
        }
        return activeRouting.updatePhysicalBypassRoute(
            connectedState = connectedState,
            physicalRoute = physicalRoute,
            tunName = connectedState.tunName,
            fwmark = runtimeSettings.fwmark,
            routeTable = runtimeSettings.routeTable,
        )
    }

    suspend fun isRootTrafficAvailable(tunAvailable: Boolean): Boolean {
        val state = stateStore.read() ?: return false
        val tproxyState = state.tproxy
        return if (state.rootConnectionBackend == RootConnectionBackend.Tproxy && tproxyState != null) {
            tproxyRouting.isHealthy(tproxyState)
        } else {
            tunAvailable
        }
    }

    suspend fun detectPhysicalRoute(tunName: String): TunManager.PhysicalRoute? = executeStep(
        ConnectionStep(
            "Physical route probe",
            progress = null,
            isSuccessful = { it != null },
            reported = false,
            action = {
                if (!rootRuntime.open()) return@ConnectionStep null
                tunGateway.detectPhysicalRoute(tunName)
            },
        ),
    )

    suspend fun restoreRootApiClients(): Boolean = executeStep(
        ConnectionStep(
            label = "Restore Xray control API",
            progress = ConnectionProgress.RestoringControlApi,
            isSuccessful = { it },
            action = ::restoreRootApiClientsOnce,
        ),
    )

    private suspend fun restoreRootApiClientsOnce(): Boolean {
        // Validate the persisted state before touching the firewall: installing loopback rules
        // for a runtime that turns out to be rootless or unreadable would leave orphaned iptables
        // state behind with nothing to reattach to.
        val state = stateStore.read() ?: return false
        // A record left by the rootless runtime describes a core that died with its process, so
        // there is nothing here to reattach to.
        if (state.physicalInterface == VPN_SERVICE_INTERFACE_LABEL) return false
        val endpoint = when (val configuredEndpoint = xrayBinary.readConfig()?.let(::parseXrayApiEndpoint)) {
            is XrayApiEndpoint.LoopbackTcp -> configuredEndpoint
            is XrayApiEndpoint.FileSystemUnixSocket,
            is XrayApiEndpoint.UnixSocket,
            -> return false
            null ->
                state.xrayApiPort
                    ?.takeIf { it in 1..65_535 }
                    ?.let { XrayApiEndpoint.LoopbackTcp(it) }
                    ?: return false
        }
        if (!rootRuntime.protectLoopbackApi(endpoint.port, environment.appUid)) return false
        runtimeState = XrayRuntimeState.Active(
            strategy = rootStrategy,
            pid = state.xrayPid,
            tunName = state.tunName,
            apiEndpoint = endpoint,
            physicalRoute = selectPersistedPhysicalRoute(state),
        )
        configWriter.active = null
        apiClients.replace(endpoint)
        return true
    }

    suspend fun disconnect(): Boolean = disconnect(updateState = true, fastCleanup = true)

    suspend fun disconnect(
        updateState: Boolean,
        fastCleanup: Boolean = false,
        preserveTproxyGuard: Boolean = false,
    ): Boolean {
        if (updateState) {
            stateCoordinator.markDisconnecting()
            log.append(LogSource.APP, "Disconnecting...")
        }
        // With no runtime of our own and nothing recorded by an earlier one, there is nothing
        // installed to take back.
        val strategy = runtimeState.strategy ?: persistedRuntimeStrategy()
        val cleaned = executeStep(
            ConnectionStep(
                label = "Release Xray runtime",
                progress = ConnectionProgress.StoppingCore,
                isSuccessful = { it },
                action = {
                    strategy
                        ?.release(fastCleanup = fastCleanup, preserveTproxyGuard = preserveTproxyGuard)
                        ?: true
                },
            ),
        )
        rootRuntimeKnownClean = cleaned && strategy?.managesSystemRouting == true && !preserveTproxyGuard
        if (rootRuntimeKnownClean) cleanup.recordKnownCleanState()
        runtimeState = XrayRuntimeState.Inactive
        configWriter.active = null
        executeStep(
            ConnectionStep("Close Xray control API", ConnectionProgress.CleaningRuntime) {
                apiClients.close()
            },
        )
        if (!cleaned) {
            stateCoordinator.markError(environment.describe(ConnectionError.CleanupFailed))
            return false
        }
        if (!preserveTproxyGuard) {
            // The release above took the guard chain back down. Carrying these flags into an unrelated
            // later connection attempt would make its failure path preserve a guard that DROPs every
            // app UID, taking the whole device offline instead of just reporting a failed connection.
            transitionGuardInstalled = false
            preserveGuardOnFailure = false
        }
        if (updateState) {
            log.append(LogSource.APP, "Disconnected")
            stateCoordinator.markDisconnected()
        }
        return true
    }

    suspend fun prepareSeamlessReconnect(): Boolean {
        val state = stateStore.read() ?: return true
        val tproxyState = state.tproxy ?: state.transitionGuard ?: return true
        val appRoutingPlan = try {
            appRoutingPlanner.build(
                baseRouteTable = state.routeTable,
                includeProxyRoutes = false,
                includeTunRoutes = true,
            )
        } catch (error: IllegalArgumentException) {
            log.append(LogSource.APP, "Could not prepare TPROXY reconnect guard: ${error.message}")
            return false
        } catch (error: IllegalStateException) {
            log.append(LogSource.APP, "Could not prepare TPROXY reconnect guard: ${error.message}")
            return false
        }
        val plan = TproxyTrafficPlan(
            runtimeState = tproxyState,
            groups = tproxyState.groups.mapIndexed { index, group ->
                com.material.xray.core.connection.routing.TproxyTrafficGroup(group, emptySet(), isBase = index == 0)
            },
            bypassUids = runtimeBypassUids(appRoutingPlan.directUids),
            routeProfileIds = appRoutingPlan.routeProfileIds,
        )
        val result = tproxyGateway.installGuard(plan)
        if (!result.success) {
            log.append(LogSource.APP, "Could not prepare TPROXY reconnect guard: ${result.error ?: "unknown error"}")
            return false
        }
        transitionGuardInstalled = true
        preserveGuardOnFailure = true
        return true
    }

    val hasTransitionGuard: Boolean
        get() = transitionGuardInstalled

    fun prepareForServiceDestruction() {
        runtimeState.strategy?.requestStop()
        apiClients.requestClose()
    }

    suspend fun ensureCleanRootRuntime(preserveTproxyGuard: Boolean = false): Boolean {
        val cleaned = executeStep(
            ConnectionStep(
                label = "Clean recorded root runtime",
                progress = ConnectionProgress.CleaningRuntime,
                isSuccessful = { it },
                action = { cleanup.ensureCleanState(preserveTproxyGuard = preserveTproxyGuard) },
            ),
        )
        rootRuntimeKnownClean = cleaned && !preserveTproxyGuard
        if (rootRuntimeKnownClean) cleanup.recordKnownCleanState()
        runtimeState = XrayRuntimeState.Inactive
        configWriter.active = null
        if (!cleaned) stateCoordinator.markError(environment.describe(ConnectionError.CleanupFailed))
        return cleaned
    }

    suspend fun clearFailedTransitionGuard() {
        if (!transitionGuardInstalled) return
        cleanup.ensureCleanState(preserveTproxyGuard = false)
        transitionGuardInstalled = false
        preserveGuardOnFailure = false
    }

    private suspend fun fail(
        message: String,
        cleanState: Boolean = true,
        retryable: Boolean = true,
    ) {
        log.append(LogSource.APP, "ERROR: $message")
        var finalMessage = message
        if (cleanState) {
            if (!releaseStartedRuntime(preserveGuardOnFailure)) {
                finalMessage = environment.describe(ConnectionError.CleanupFailed)
                log.append(LogSource.APP, "ERROR: $finalMessage")
            }
            if (transitionGuardInstalled && !preserveGuardOnFailure) {
                if (!tproxyGateway.removeGuard()) {
                    finalMessage = environment.describe(ConnectionError.CleanupFailed)
                    log.append(LogSource.APP, "ERROR: $finalMessage")
                } else {
                    transitionGuardInstalled = false
                }
            }
            runtimeState = XrayRuntimeState.Inactive
            configWriter.active = null
        }
        apiClients.close()
        stateCoordinator.markError(finalMessage, retryable)
    }

    private suspend fun cleanCancelledConnectionAttempt() {
        val cleanupErrors = mutableListOf<Exception>()
        try {
            if (!releaseStartedRuntime(preserveGuardOnFailure)) {
                log.append(LogSource.APP, "ERROR: Could not clean up cancelled Xray startup")
            }
        } catch (error: Exception) {
            cleanupErrors += error
        } finally {
            runtimeState = XrayRuntimeState.Inactive
            configWriter.active = null
            if (transitionGuardInstalled && !preserveGuardOnFailure) {
                try {
                    if (tproxyGateway.removeGuard()) transitionGuardInstalled = false
                } catch (error: Exception) {
                    cleanupErrors += error
                }
            }
            try {
                apiClients.close()
            } catch (error: Exception) {
                cleanupErrors += error
            }
        }
        cleanupErrors.forEach { error ->
            log.append(LogSource.APP, "ERROR: Could not clean up cancelled Xray startup: ${error.message}")
        }
        stateCoordinator.markDisconnected()
    }

    override suspend fun isProcessAlive(pid: Int): Boolean = processFor(pid)?.isAlive(pid) ?: false

    suspend fun isRestorableRootProcessAlive(pid: Int): Boolean = executeStep(
        ConnectionStep(
            "Restored Xray process check",
            ConnectionProgress.VerifyingRuntime,
            isSuccessful = { it },
            action = { processSupervisor.isAlive(pid) },
        ),
    )

    suspend fun isRestorableRootRoutingAvailable(state: XrayState, tunAvailable: Boolean): Boolean = executeStep(
        ConnectionStep(
            "Restored routing check",
            ConnectionProgress.VerifyingRuntime,
            isSuccessful = { it },
            action = {
                val tproxyState = state.tproxy
                if (state.rootConnectionBackend == RootConnectionBackend.Tproxy && tproxyState != null) {
                    tproxyRouting.verify(tproxyState)
                } else {
                    tunAvailable
                }
            },
        ),
    )

    suspend fun killProcess(pid: Int, signal: Int = 15): Boolean = processFor(pid)?.kill(pid, signal) ?: false

    override suspend fun readProcessResidentMemoryMb(pid: Int): Long? = processFor(pid)?.readResidentMemoryMb(pid)

    suspend fun readActiveConnectionCount(pid: Int): Int? = processFor(pid)?.readActiveConnectionCount(pid)

    suspend fun readProcessMetrics(pid: Int): ProcessMetrics? = processFor(pid)?.readProcessMetrics(pid)

    suspend fun readOutboundTrafficStatsBytes(): Map<String, Long> = apiClients.readOutboundTrafficStatsBytes()

    override suspend fun readXraySysStats(): XraySysStats? = apiClients.readSysStats()

    override suspend fun readCrashReason(): String = runCatching {
        runtimeState.strategy?.readCrashReason()
    }.getOrNull() ?: "xray process exited"

    suspend fun readBalancerSelection(balancerTag: String) = apiClients.readBalancerSelection(balancerTag)

    private fun runtimeBypassUids(directUids: Set<Int>): Set<Int> {
        val appUid = environment.appUid
        return if (appUid > 0) directUids + appUid else directUids
    }

    private fun strategyFor(useRootService: Boolean): XrayRuntimeStrategy = if (useRootService) {
        rootStrategy
    } else {
        vpnServiceStrategy
    }

    /** Resolves the core that [pid] belongs to, or null when this manager did not start it. */
    private fun processFor(pid: Int): XrayRuntimeProcess? = (runtimeState as? XrayRuntimeState.Active)
        ?.takeIf { it.pid == pid }
        ?.strategy

    private suspend fun persistedRuntimeStrategy(): XrayRuntimeStrategy? = stateStore.read()?.let { state ->
        strategyFor(useRootService = state.physicalInterface != VPN_SERVICE_INTERFACE_LABEL)
    }

    /**
     * Releases the runtime a connection attempt had already started.
     *
     * The root fallback covers teardown after the runtime was already reset, which happens when a
     * failure is itself cancelled part-way through; root cleanup is the safe choice there because
     * it is the only runtime that can leave routing behind.
     */
    private suspend fun releaseStartedRuntime(preserveTproxyGuard: Boolean = false): Boolean {
        val strategy = runtimeState.strategy ?: rootStrategy
        val cleaned = strategy.release(fastCleanup = false, preserveTproxyGuard = preserveTproxyGuard)
        rootRuntimeKnownClean = cleaned && strategy.managesSystemRouting && !preserveTproxyGuard
        if (rootRuntimeKnownClean) cleanup.recordKnownCleanState()
        return cleaned
    }

    private fun selectPersistedPhysicalRoute(state: XrayState): TunManager.PhysicalRoute? = state.physicalInterface
        ?.takeIf { it.isNotBlank() && it != VPN_SERVICE_INTERFACE_LABEL }
        ?.let { physicalInterface ->
            TunManager.PhysicalRoute(
                dev = physicalInterface,
                gateway = state.physicalGateway,
                table = state.physicalTable,
            )
        }

    private suspend fun <T> executeStep(step: ConnectionStep<T>): T = stepExecutor.execute(step)
}

private sealed interface XrayRuntimeState {
    val strategy: XrayRuntimeStrategy?

    data object Inactive : XrayRuntimeState {
        override val strategy: XrayRuntimeStrategy? = null
    }

    data class Starting(
        override val strategy: XrayRuntimeStrategy,
    ) : XrayRuntimeState

    data class Active(
        override val strategy: XrayRuntimeStrategy,
        val pid: Int,
        val tunName: String,
        val apiEndpoint: XrayApiEndpoint,
        val physicalRoute: TunManager.PhysicalRoute?,
    ) : XrayRuntimeState
}

private data class TproxyPlanPreparation(val plan: TproxyTrafficPlan?)

private fun effectiveRootBackend(
    managesSystemRouting: Boolean,
    configuredBackend: RootConnectionBackend,
): RootConnectionBackend = if (managesSystemRouting) configuredBackend else RootConnectionBackend.Tun

private const val LEGACY_DEFAULT_TUN_NAME = "xray0"
const val TPROXY_INTERFACE_LABEL = "TPROXY"
private const val CONNECTION_STEP_MAX_RETRIES = 2
private const val CONNECTION_STEP_RETRY_DELAY_MS = 1_500L
private const val XRAY_API_READY_TIMEOUT_MS = 10_000L
private const val XRAY_API_READY_RETRY_DELAY_MS = 100L
