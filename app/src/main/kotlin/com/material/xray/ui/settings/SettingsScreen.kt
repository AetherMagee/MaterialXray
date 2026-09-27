package com.material.xray.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.material.xray.R
import com.material.xray.core.locale.setAppLocales
import com.material.xray.data.repository.BackupSummary
import com.material.xray.data.repository.SettingsSnapshot
import com.material.xray.model.GeoDataUpdateInterval
import com.material.xray.model.NotificationField
import com.material.xray.model.NotificationSettings
import com.material.xray.model.NotificationStyle
import com.material.xray.model.XrayRuntimeSettings
import com.material.xray.ui.components.DropdownOption
import com.material.xray.ui.components.ReadOnlyDropdownField
import com.material.xray.ui.components.ScrolledTopAppBar
import com.material.xray.ui.components.SelectableOptionRow
import com.material.xray.ui.components.SettingsSwitchRow
import com.material.xray.ui.components.rememberSystemState
import com.material.xray.ui.text.descriptionResource
import com.material.xray.ui.text.labelResource
import com.material.xray.ui.theme.DefaultBlueDarkColorScheme
import com.material.xray.ui.theme.DefaultBlueLightColorScheme
import java.util.Locale
import kotlinx.coroutines.flow.collect
import org.xmlpull.v1.XmlPullParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(showTitleBarLogo: Boolean, viewModel: SettingsViewModel = hiltViewModel()) {
    val persistedSettings by viewModel.settings.collectAsStateWithLifecycle()
    val settings = persistedSettings
    // The DNS subpage is local state rather than a navigation destination, because the app keeps a
    // single flat graph of tabs. It is drawn over the settings list rather than swapped with it, so
    // the list keeps its scroll position and its event collectors while the subpage is open.
    var showDnsSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showDnsSettings) { showDnsSettings = false }

    if (settings == null) {
        SettingsLoadingScreen(showTitleBarLogo)
        return
    }

    Box {
        SettingsScreenContent(
            viewModel = viewModel,
            settings = settings,
            onOpenDnsSettings = { showDnsSettings = true },
        )

        AnimatedContent(
            targetState = showDnsSettings,
            transitionSpec = {
                fadeIn(tween(SUBPAGE_FADE_MS)) togetherWith fadeOut(tween(SUBPAGE_FADE_MS)) using null
            },
            label = "dnsSettings",
        ) { dnsSettingsOpen ->
            if (dnsSettingsOpen) {
                DnsSettingsScreen(
                    settings = settings,
                    viewModel = viewModel,
                    onBack = { showDnsSettings = false },
                )
            }
        }
    }
}

private const val SUBPAGE_FADE_MS = 180

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("CyclomaticComplexMethod")
@Composable
private fun SettingsScreenContent(
    viewModel: SettingsViewModel,
    settings: SettingsSnapshot,
    onOpenDnsSettings: () -> Unit,
) {
    val rootAvailable by viewModel.rootAvailable.collectAsStateWithLifecycle()
    val tproxyCompatibility by viewModel.tproxyCompatibility.collectAsStateWithLifecycle()
    val geoipUpdating by viewModel.geoipUpdating.collectAsStateWithLifecycle()
    val geositeUpdating by viewModel.geositeUpdating.collectAsStateWithLifecycle()
    val geoDataClearing by viewModel.geoDataClearing.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val geoDataDownloadProgress by viewModel.geoDataDownloadProgress.collectAsStateWithLifecycle()
    val xrayCoreVersion by viewModel.xrayCoreVersion.collectAsStateWithLifecycle()
    val databaseResetting by viewModel.databaseResetting.collectAsStateWithLifecycle()
    val backupBusy by viewModel.backupBusy.collectAsStateWithLifecycle()
    val backupImportSummary by viewModel.backupImportSummary.collectAsStateWithLifecycle()
    val appUpdateCheckStatus by viewModel.appUpdateCheckStatus.collectAsStateWithLifecycle()
    val oemAutostartGuidance by viewModel.oemAutostartGuidance.collectAsStateWithLifecycle()
    val selectedSubscriptionRequiresHwid by viewModel.selectedSubscriptionRequiresHwid.collectAsStateWithLifecycle()

    val tunName = settings.tunName
    val dnsServers = settings.dnsServers
    val domesticDnsServers = settings.domesticDnsServers
    val autoConnect = settings.autoConnect
    val useRootService = settings.useRootService
    val rootConnectionBackend = settings.rootConnectionBackend
    val bypassLan = settings.bypassLan
    val tunnelTetheredClients = settings.tunnelTetheredClients
    val allowIpv6 = settings.allowIpv6
    val xrayBufferSizeKiB = settings.xrayBufferSizeKiB
    val tunMtu = settings.tunMtu
    val xrayMemoryRestartThresholdMiB = settings.xrayMemoryRestartThresholdMiB
    val passiveHealthMonitoringEnabled = settings.passiveHealthMonitoringEnabled
    val xrayLogLevel = settings.xrayLogLevel
    val defaultOutbound = settings.defaultOutbound
    val launcherIcon = settings.launcherIcon
    val themePreset = settings.themePreset
    val oledDark = settings.oledDark
    val showTitleBarLogo = settings.showTitleBarLogo
    val floatingConnectButton = settings.floatingConnectButton
    val showAdvancedOptions = settings.showAdvancedOptions
    val notificationSettings = settings.notificationSettings
    val subscriptionSendHardwareId = settings.subscriptionSendHardwareId
    val routingPolicyControl = settings.routingPolicyControl
    val geoipUrl = settings.geoipUrl
    val geositeUrl = settings.geositeUrl
    val geoDataUpdateIntervalHours = settings.geoDataUpdateIntervalHours
    val latencyCheckUrl = settings.latencyCheckUrl
    val sortOutboundsByLatency = settings.sortOutboundsByLatency
    val showBothLatencyResults = settings.showBothLatencyResults
    val appUpdateChecksEnabled = settings.appUpdateChecksEnabled
    val diagnosticsEnabled = settings.diagnosticsEnabled
    val geoDataOperationInProgress = geoipUpdating || geositeUpdating || geoDataClearing
    val rootServiceAvailable = rootAvailable != false
    val rootServiceActive = useRootService && rootAvailable == true
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val context = LocalContext.current
    val dynamicColorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) {
            androidx.compose.material3.dynamicDarkColorScheme(context)
        } else {
            androidx.compose.material3.dynamicLightColorScheme(context)
        }
    } else if (darkTheme) {
        DefaultBlueDarkColorScheme
    } else {
        DefaultBlueLightColorScheme
    }
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val notificationAccessState = rememberSystemState { notificationAccess(it) }
    val scrollState = rememberLazyListState()
    val themeScrollState = rememberScrollState()
    val supportedLocales = remember(resources) { resources.loadSupportedAppLocales() }
    val selectedLocale = AppCompatDelegate.getApplicationLocales()[0]

    var serviceExpanded by rememberSaveable { mutableStateOf(true) }
    var serversExpanded by rememberSaveable { mutableStateOf(false) }
    var appearanceExpanded by rememberSaveable { mutableStateOf(false) }
    var notificationsExpanded by rememberSaveable { mutableStateOf(false) }
    var coreExpanded by rememberSaveable { mutableStateOf(false) }
    var dataExpanded by rememberSaveable { mutableStateOf(false) }
    var aboutExpanded by rememberSaveable { mutableStateOf(false) }

    var showRootAccessDeniedDialog by rememberSaveable { mutableStateOf(false) }
    var showNotificationFieldsDialog by rememberSaveable { mutableStateOf(false) }
    var showFieldStyleDialog by rememberSaveable { mutableStateOf(false) }
    var showUpdateFrequencyDialog by rememberSaveable { mutableStateOf(false) }
    var showResetDatabaseDialog by rememberSaveable { mutableStateOf(false) }
    var showOpenSourceLicensesDialog by rememberSaveable { mutableStateOf(false) }
    var showNotificationAccessDialog by rememberSaveable { mutableStateOf(false) }
    var showAppLanguageDialog by rememberSaveable { mutableStateOf(false) }

    var editingTunName by rememberSaveable(tunName) { mutableStateOf(tunName) }
    var editingXrayBufferSizeKiB by rememberSaveable(xrayBufferSizeKiB) { mutableStateOf(xrayBufferSizeKiB.toString()) }
    var editingTunMtu by rememberSaveable(tunMtu) { mutableStateOf(tunMtu.toString()) }
    var editingXrayMemoryRestartThresholdMiB by rememberSaveable(xrayMemoryRestartThresholdMiB) {
        mutableStateOf(xrayMemoryRestartThresholdMiB.toString())
    }
    var editingGeoipUrl by rememberSaveable(geoipUrl) { mutableStateOf(geoipUrl) }
    var editingGeositeUrl by rememberSaveable(geositeUrl) { mutableStateOf(geositeUrl) }
    var editingGeoDataUpdateIntervalHours by rememberSaveable(geoDataUpdateIntervalHours) {
        mutableStateOf(geoDataUpdateIntervalHours.toString())
    }
    var editingLatencyCheckUrl by rememberSaveable(latencyCheckUrl) { mutableStateOf(latencyCheckUrl) }

    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val hasTunNameChanges by remember(editingTunName, tunName) { derivedStateOf { editingTunName != tunName } }
    val parsedXrayBufferSizeKiB by remember(editingXrayBufferSizeKiB) {
        derivedStateOf { editingXrayBufferSizeKiB.toIntOrNull() }
    }
    val parsedTunMtu by remember(editingTunMtu) { derivedStateOf { editingTunMtu.toIntOrNull() } }
    val parsedXrayMemoryRestartThresholdMiB by remember(editingXrayMemoryRestartThresholdMiB) {
        derivedStateOf { editingXrayMemoryRestartThresholdMiB.toIntOrNull() }
    }
    val parsedGeoDataUpdateIntervalHours by remember(editingGeoDataUpdateIntervalHours) {
        derivedStateOf { editingGeoDataUpdateIntervalHours.toIntOrNull() }
    }
    val isXrayBufferSizeKiBValid by remember(parsedXrayBufferSizeKiB) {
        derivedStateOf { parsedXrayBufferSizeKiB?.let(XrayRuntimeSettings::isValidXrayBufferSizeKiB) == true }
    }
    val isTunMtuValid by remember(parsedTunMtu) {
        derivedStateOf { parsedTunMtu?.let(XrayRuntimeSettings::isValidTunMtu) == true }
    }
    val isXrayMemoryRestartThresholdMiBValid by remember(parsedXrayMemoryRestartThresholdMiB) {
        derivedStateOf {
            parsedXrayMemoryRestartThresholdMiB
                ?.let(XrayRuntimeSettings::isValidXrayMemoryRestartThresholdMiB) == true
        }
    }
    val isGeoDataUpdateIntervalHoursValid by remember(parsedGeoDataUpdateIntervalHours) {
        derivedStateOf { parsedGeoDataUpdateIntervalHours?.let(GeoDataUpdateInterval::isValid) == true }
    }
    val hasXrayBufferSizeKiBChanges by remember(editingXrayBufferSizeKiB, xrayBufferSizeKiB) {
        derivedStateOf { editingXrayBufferSizeKiB != xrayBufferSizeKiB.toString() }
    }
    val hasTunMtuChanges by remember(editingTunMtu, tunMtu) {
        derivedStateOf { editingTunMtu != tunMtu.toString() }
    }
    val hasXrayMemoryRestartThresholdMiBChanges by remember(
        editingXrayMemoryRestartThresholdMiB,
        xrayMemoryRestartThresholdMiB,
    ) {
        derivedStateOf { editingXrayMemoryRestartThresholdMiB != xrayMemoryRestartThresholdMiB.toString() }
    }
    val hasGeoipUrlChanges by remember(editingGeoipUrl, geoipUrl) {
        derivedStateOf { editingGeoipUrl.trim() != geoipUrl }
    }
    val hasGeositeUrlChanges by remember(editingGeositeUrl, geositeUrl) {
        derivedStateOf { editingGeositeUrl.trim() != geositeUrl }
    }
    val hasGeoDataUpdateIntervalHoursChanges by remember(
        editingGeoDataUpdateIntervalHours,
        geoDataUpdateIntervalHours,
    ) {
        derivedStateOf { editingGeoDataUpdateIntervalHours != geoDataUpdateIntervalHours.toString() }
    }
    val hasLatencyCheckUrlChanges by remember(editingLatencyCheckUrl, latencyCheckUrl) {
        derivedStateOf { editingLatencyCheckUrl.trim() != latencyCheckUrl }
    }
    val xrayCoreVersionText = xrayCoreVersionText(xrayCoreVersion)
    val appUpdateCheckDescription = appUpdateCheckStatus?.let { appUpdateCheckDescription(it) }
    val appVersion = remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }
    val appVersionText = if (appVersion == null) {
        stringResource(R.string.settings_app_version_unknown, stringResource(R.string.app_name))
    } else {
        stringResource(R.string.settings_app_version, stringResource(R.string.app_name), appVersion)
    }
    val appLanguageName = selectedLocale?.nativeDisplayName()
        ?: stringResource(R.string.settings_app_language_system_default)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.prepareBackupImport(it) } }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        context.recordNotificationPermissionRequest()
        notificationAccessState.refresh()
    }

    rememberSystemState { viewModel.refreshOemAutostartGuidance() }

    LaunchedEffect(viewModel, context, resources) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.assetUpdateEvents.collect { message ->
                val text = message.detail?.let { detail ->
                    resources.getString(message.messageResId, detail)
                } ?: resources.getString(message.messageResId)
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackupOperationEventEffect(viewModel)

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.rootAccessDeniedEvents.collect {
                showRootAccessDeniedDialog = true
            }
        }
    }

    LaunchedEffect(viewModel, context, resources) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.databaseResetEvents.collect { success ->
                if (success) {
                    (context as? Activity)?.finishAndRemoveTask()
                        ?: Process.killProcess(Process.myPid())
                } else {
                    Toast.makeText(
                        context,
                        resources.getString(R.string.settings_internal_database_reset_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            ScrolledTopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = topAppBarScrollBehavior,
                showLogo = showTitleBarLogo,
            )
        },
    ) { padding ->
        LazyColumn(
            state = scrollState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            serviceSection(
                rootAvailable = rootAvailable,
                rootServiceAvailable = rootServiceAvailable,
                rootServiceActive = rootServiceActive,
                useRootService = useRootService,
                rootConnectionBackend = rootConnectionBackend,
                tunnelTetheredClients = tunnelTetheredClients,
                tproxyCompatibility = tproxyCompatibility,
                autoConnect = autoConnect,
                oemAutostartGuidance = oemAutostartGuidance,
                onUseRootServiceChange = viewModel::setUseRootService,
                onRootConnectionBackendChange = viewModel::setRootConnectionBackend,
                onTunnelTetheredClientsChange = viewModel::setTunnelTetheredClients,
                onRetryTproxyCompatibility = viewModel::retryTproxyCompatibilityCheck,
                onAutoConnectChange = viewModel::setAutoConnect,
                onOpenOemAutostartSettings = viewModel::openOemAutostartSettings,
                expanded = serviceExpanded,
                onExpandedChange = { serviceExpanded = it },
            )
            routingSection(
                bypassLan = bypassLan,
                allowIpv6 = allowIpv6,
                dnsServers = dnsServers,
                domesticDnsServers = domesticDnsServers,
                routingPolicyControl = routingPolicyControl,
                ipv6SelectionEnabled = isIpv6SelectionEnabled(
                    rootServiceActive = rootServiceActive,
                    backend = rootConnectionBackend,
                    compatibility = tproxyCompatibility,
                ),
                onBypassLanChange = viewModel::setBypassLan,
                onAllowIpv6Change = viewModel::setAllowIpv6,
                onRoutingPolicyControlChange = viewModel::setRoutingPolicyControl,
                expanded = serviceExpanded,
            )
            connectionDnsSection(
                onOpenDnsSettings = onOpenDnsSettings,
                expanded = serviceExpanded,
            )
            connectionHardwareIdSection(
                subscriptionSendHardwareId = subscriptionSendHardwareId,
                hwidLockedBySubscription = selectedSubscriptionRequiresHwid && subscriptionSendHardwareId,
                onSubscriptionSendHardwareIdChange = viewModel::setSubscriptionSendHardwareId,
                expanded = serviceExpanded,
            )

            serversSection(
                sortOutboundsByLatency = sortOutboundsByLatency,
                showBothLatencyResults = showBothLatencyResults,
                showAdvancedOptions = showAdvancedOptions,
                editingLatencyCheckUrl = editingLatencyCheckUrl,
                hasLatencyCheckUrlChanges = hasLatencyCheckUrlChanges,
                onSortOutboundsByLatencyChange = viewModel::setSortOutboundsByLatency,
                onShowBothLatencyResultsChange = viewModel::setShowBothLatencyResults,
                onEditingLatencyCheckUrlChange = { editingLatencyCheckUrl = it },
                onSaveLatencyCheckUrl = { viewModel.setLatencyCheckUrl(editingLatencyCheckUrl) },
                expanded = serversExpanded,
                onExpandedChange = { serversExpanded = it },
            )

            appearanceSection(
                darkTheme = darkTheme,
                dynamicColorScheme = dynamicColorScheme,
                themeScrollState = themeScrollState,
                themePreset = themePreset,
                oledDark = oledDark,
                appLanguageName = appLanguageName,
                launcherIcon = launcherIcon,
                showTitleBarLogo = showTitleBarLogo,
                floatingConnectButton = floatingConnectButton,
                onThemePresetChange = viewModel::setThemePreset,
                onOledDarkChange = viewModel::setOledDark,
                onAppLanguageClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APP_LOCALE_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    } else {
                        showAppLanguageDialog = true
                    }
                },
                onLauncherIconChange = viewModel::setLauncherIcon,
                onShowTitleBarLogoChange = viewModel::setShowTitleBarLogo,
                onFloatingConnectButtonChange = viewModel::setFloatingConnectButton,
                expanded = appearanceExpanded,
                onExpandedChange = { appearanceExpanded = it },
            )

            notificationSection(
                settings = notificationSettings,
                access = notificationAccessState.value,
                onRequestAccess = { showNotificationAccessDialog = true },
                onConfigureFields = { showNotificationFieldsDialog = true },
                onConfigureStyle = { showFieldStyleDialog = true },
                onConfigureFrequency = { showUpdateFrequencyDialog = true },
                expanded = notificationsExpanded,
                onExpandedChange = { notificationsExpanded = it },
            )

            coreSection(
                rootServiceActive = rootServiceActive,
                rootConnectionBackend = rootConnectionBackend,
                showAdvancedOptions = showAdvancedOptions,
                editingTunName = editingTunName,
                hasTunNameChanges = hasTunNameChanges,
                editingXrayBufferSizeKiB = editingXrayBufferSizeKiB,
                isXrayBufferSizeKiBValid = isXrayBufferSizeKiBValid,
                hasXrayBufferSizeKiBChanges = hasXrayBufferSizeKiBChanges,
                editingTunMtu = editingTunMtu,
                isTunMtuValid = isTunMtuValid,
                hasTunMtuChanges = hasTunMtuChanges,
                editingXrayMemoryRestartThresholdMiB = editingXrayMemoryRestartThresholdMiB,
                isXrayMemoryRestartThresholdMiBValid = isXrayMemoryRestartThresholdMiBValid,
                hasXrayMemoryRestartThresholdMiBChanges = hasXrayMemoryRestartThresholdMiBChanges,
                passiveHealthMonitoringEnabled = passiveHealthMonitoringEnabled,
                defaultOutbound = defaultOutbound,
                xrayLogLevel = xrayLogLevel,
                onEditingTunNameChange = { editingTunName = it },
                onSaveTunName = { viewModel.setTunName(editingTunName) },
                onShowAdvancedOptionsChange = viewModel::setShowAdvancedOptions,
                onEditingXrayBufferSizeKiBChange = { editingXrayBufferSizeKiB = it },
                onSaveXrayBufferSizeKiB = { parsedXrayBufferSizeKiB?.let(viewModel::setXrayBufferSizeKiB) },
                onEditingTunMtuChange = { editingTunMtu = it },
                onSaveTunMtu = { parsedTunMtu?.let(viewModel::setTunMtu) },
                onEditingXrayMemoryRestartThresholdMiBChange = { editingXrayMemoryRestartThresholdMiB = it },
                onSaveXrayMemoryRestartThresholdMiB = {
                    parsedXrayMemoryRestartThresholdMiB?.let(viewModel::setXrayMemoryRestartThresholdMiB)
                },
                onPassiveHealthMonitoringEnabledChange = viewModel::setPassiveHealthMonitoringEnabled,
                onDefaultOutboundChange = viewModel::setDefaultOutbound,
                onXrayLogLevelChange = viewModel::setXrayLogLevel,
                expanded = coreExpanded,
                onExpandedChange = { coreExpanded = it },
            )

            dataSection(
                showAdvancedOptions = showAdvancedOptions,
                diagnosticsEnabled = diagnosticsEnabled,
                geoipUpdating = geoipUpdating,
                geositeUpdating = geositeUpdating,
                geoDataClearing = geoDataClearing,
                geoDataOperationInProgress = geoDataOperationInProgress,
                connectionState = connectionState,
                geoDataDownloadProgress = geoDataDownloadProgress,
                editingGeoDataUpdateIntervalHours = editingGeoDataUpdateIntervalHours,
                isGeoDataUpdateIntervalHoursValid = isGeoDataUpdateIntervalHoursValid,
                hasGeoDataUpdateIntervalHoursChanges = hasGeoDataUpdateIntervalHoursChanges,
                editingGeoipUrl = editingGeoipUrl,
                hasGeoipUrlChanges = hasGeoipUrlChanges,
                editingGeositeUrl = editingGeositeUrl,
                hasGeositeUrlChanges = hasGeositeUrlChanges,
                backupBusy = backupBusy,
                databaseResetting = databaseResetting,
                onDiagnosticsEnabledChange = viewModel::setDiagnosticsEnabled,
                onEditingGeoDataUpdateIntervalHoursChange = { editingGeoDataUpdateIntervalHours = it },
                onSaveGeoDataUpdateIntervalHours = {
                    parsedGeoDataUpdateIntervalHours?.let(viewModel::setGeoDataUpdateIntervalHours)
                },
                onEditingGeoipUrlChange = { editingGeoipUrl = it },
                onSaveGeoipUrl = { viewModel.setGeoipUrl(editingGeoipUrl) },
                onUpdateGeoipAsset = viewModel::updateGeoipAsset,
                onEditingGeositeUrlChange = { editingGeositeUrl = it },
                onSaveGeositeUrl = { viewModel.setGeositeUrl(editingGeositeUrl) },
                onUpdateGeositeAsset = viewModel::updateGeositeAsset,
                onExportBackup = { exportLauncher.launch("material-xray-backup.json") },
                onImportBackup = { importLauncher.launch(arrayOf("application/json")) },
                onClearGeoData = viewModel::clearGeoData,
                onResetDatabase = { showResetDatabaseDialog = true },
                expanded = dataExpanded,
                onExpandedChange = { dataExpanded = it },
            )

            aboutSection(
                appUpdateChecksEnabled = appUpdateChecksEnabled,
                appUpdateCheckStatus = appUpdateCheckStatus,
                appUpdateCheckDescription = appUpdateCheckDescription,
                appVersionText = appVersionText,
                xrayCoreVersionText = xrayCoreVersionText,
                onAppUpdateChecksEnabledChange = viewModel::setAppUpdateChecksEnabled,
                onCheckForUpdates = viewModel::checkForAppUpdate,
                onOpenLicenses = { showOpenSourceLicensesDialog = true },
                expanded = aboutExpanded,
                onExpandedChange = { aboutExpanded = it },
            )
        }
    }

    SettingsDialogs(
        showRootAccessDeniedDialog = showRootAccessDeniedDialog,
        showNotificationFieldsDialog = showNotificationFieldsDialog,
        showUpdateFrequencyDialog = showUpdateFrequencyDialog,
        showFieldStyleDialog = showFieldStyleDialog,
        showResetDatabaseDialog = showResetDatabaseDialog,
        backupImportSummary = backupImportSummary,
        backupBusy = backupBusy,
        notificationSettings = notificationSettings,
        onDismissRootAccessDenied = { showRootAccessDeniedDialog = false },
        onDismissNotificationFields = { showNotificationFieldsDialog = false },
        onDismissUpdateFrequency = { showUpdateFrequencyDialog = false },
        onDismissFieldStyle = { showFieldStyleDialog = false },
        onDismissResetDatabase = { showResetDatabaseDialog = false },
        onResetDatabase = {
            showResetDatabaseDialog = false
            viewModel.resetInternalDatabase()
        },
        onDismissBackupImport = viewModel::dismissBackupImport,
        onConfirmBackupImport = viewModel::confirmBackupImport,
        onFieldEnabledChange = viewModel::setNotificationFieldEnabled,
        onReorderFields = viewModel::setNotificationFieldOrder,
        onUpdateFrequency = viewModel::setNotificationUpdateIntervalMs,
        onSelectFieldStyle = viewModel::setNotificationStyle,
    )
    if (showOpenSourceLicensesDialog) {
        OpenSourceLicensesDialog(onDismiss = { showOpenSourceLicensesDialog = false })
    }
    if (showNotificationAccessDialog) {
        NotificationPermissionDialog(
            access = notificationAccessState.value,
            onDismiss = { showNotificationAccessDialog = false },
            onAllow = {
                when (notificationAccessState.value) {
                    NotificationAccess.Available -> Unit
                    NotificationAccess.Requestable,
                    NotificationAccess.Rationale,
                    -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

                    NotificationAccess.SystemSettings -> context.openNotificationSettings()
                }
            },
        )
    }
    if (showAppLanguageDialog) {
        AppLanguageDialog(
            supportedLocales = supportedLocales,
            selectedLocale = selectedLocale,
            onDismiss = { showAppLanguageDialog = false },
            onSelect = { locale ->
                showAppLanguageDialog = false
                setAppLocales(
                    if (locale == null) {
                        LocaleListCompat.getEmptyLocaleList()
                    } else {
                        LocaleListCompat.create(locale)
                    },
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsLoadingScreen(showTitleBarLogo: Boolean) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            ScrolledTopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior,
                showLogo = showTitleBarLogo,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun BackupOperationEventEffect(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, context, resources) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.backupEvents.collect { message ->
                val text = message.detail?.let { detail ->
                    resources.getString(message.messageResId, detail)
                } ?: resources.getString(message.messageResId)
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
private fun SettingsDialogs(
    showRootAccessDeniedDialog: Boolean,
    showNotificationFieldsDialog: Boolean,
    showUpdateFrequencyDialog: Boolean,
    showFieldStyleDialog: Boolean,
    showResetDatabaseDialog: Boolean,
    backupImportSummary: BackupSummary?,
    backupBusy: Boolean,
    notificationSettings: NotificationSettings,
    onDismissRootAccessDenied: () -> Unit,
    onDismissNotificationFields: () -> Unit,
    onDismissUpdateFrequency: () -> Unit,
    onDismissFieldStyle: () -> Unit,
    onDismissResetDatabase: () -> Unit,
    onResetDatabase: () -> Unit,
    onDismissBackupImport: () -> Unit,
    onConfirmBackupImport: () -> Unit,
    onFieldEnabledChange: (NotificationField, Boolean) -> Unit,
    onReorderFields: (List<NotificationField>) -> Unit,
    onUpdateFrequency: (Int) -> Unit,
    onSelectFieldStyle: (NotificationStyle) -> Unit,
) {
    if (backupImportSummary != null) {
        AlertDialog(
            onDismissRequest = onDismissBackupImport,
            title = { Text(stringResource(R.string.settings_backup_import_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_backup_import_confirmation,
                        backupImportSummary.subscriptionCount,
                        backupImportSummary.serverCount,
                        backupImportSummary.appRouteCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !backupBusy,
                    onClick = onConfirmBackupImport,
                ) {
                    Text(
                        if (backupBusy) {
                            stringResource(R.string.settings_backup_importing)
                        } else {
                            stringResource(R.string.settings_import)
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !backupBusy,
                    onClick = onDismissBackupImport,
                ) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    if (showRootAccessDeniedDialog) {
        AlertDialog(
            onDismissRequest = onDismissRootAccessDenied,
            text = { Text(stringResource(R.string.settings_root_access_denied)) },
            confirmButton = {
                Button(onClick = onDismissRootAccessDenied) {
                    Text(stringResource(R.string.settings_ok))
                }
            },
        )
    }

    if (showNotificationFieldsDialog) {
        NotificationFieldsDialog(
            settings = notificationSettings,
            onDismiss = onDismissNotificationFields,
            onFieldEnabledChange = onFieldEnabledChange,
            onReorder = onReorderFields,
        )
    }

    if (showUpdateFrequencyDialog) {
        UpdateFrequencyDialog(
            currentValue = notificationSettings.updateIntervalMs,
            onDismiss = onDismissUpdateFrequency,
            onConfirm = {
                onUpdateFrequency(it)
                onDismissUpdateFrequency()
            },
        )
    }

    if (showFieldStyleDialog) {
        FieldStyleDialog(
            selected = notificationSettings.style,
            onDismiss = onDismissFieldStyle,
            onSelect = onSelectFieldStyle,
        )
    }

    if (showResetDatabaseDialog) {
        AlertDialog(
            onDismissRequest = onDismissResetDatabase,
            title = { Text(stringResource(R.string.settings_reset_internal_database_title)) },
            text = { Text(stringResource(R.string.settings_reset_internal_database_confirmation)) },
            confirmButton = {
                TextButton(onClick = onResetDatabase) {
                    Text(
                        text = stringResource(R.string.settings_reset),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissResetDatabase) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun xrayCoreVersionText(xrayCoreVersion: String?): String = when (xrayCoreVersion) {
    null -> stringResource(R.string.settings_xray_core_version_detecting)
    "unknown" -> stringResource(R.string.settings_xray_core_version_unknown)
    else -> stringResource(R.string.settings_xray_core_version, xrayCoreVersion)
}

@Composable
private fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val legalDocuments = legalDocuments()
    var selectedDocumentPath by remember { mutableStateOf(legalDocuments.first().assetPath) }
    val selectedDocument = legalDocuments.first { it.assetPath == selectedDocumentPath }
    val documentText = remember(context, selectedDocument) {
        context.assets.open(selectedDocument.assetPath).bufferedReader().use { it.readText() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_open_source_licenses)) },
        text = {
            Column(
                modifier = Modifier.heightIn(min = 320.dp, max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReadOnlyDropdownField(
                    label = stringResource(R.string.settings_legal_document),
                    selectedText = selectedDocument.label,
                    options = legalDocuments.map { document ->
                        DropdownOption(value = document.assetPath, label = document.label)
                    },
                    onSelected = { selectedDocumentPath = it },
                )
                key(selectedDocument) {
                    Text(
                        text = documentText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_done))
            }
        },
    )
}

private data class LegalDocument(
    val label: String,
    val assetPath: String,
)

@Composable
private fun legalDocuments(): List<LegalDocument> = listOf(
    LegalDocument(stringResource(R.string.settings_legal_third_party_notices), "legal/THIRD_PARTY_NOTICES.md"),
    LegalDocument(stringResource(R.string.settings_legal_gpl), "legal/licenses/GPL-3.0-or-later.txt"),
    LegalDocument(stringResource(R.string.settings_legal_mpl), "legal/licenses/MPL-2.0.txt"),
    LegalDocument(stringResource(R.string.settings_legal_apache), "legal/licenses/Apache-2.0.txt"),
    LegalDocument(stringResource(R.string.settings_legal_bsd), "legal/licenses/BSD-3-Clause.txt"),
    LegalDocument(stringResource(R.string.settings_legal_mit), "legal/licenses/MIT.txt"),
    LegalDocument(stringResource(R.string.settings_legal_xray_source), "legal/xray/SOURCE.md"),
    LegalDocument(stringResource(R.string.settings_legal_xray_version), "legal/xray/VERSION"),
    LegalDocument(stringResource(R.string.settings_legal_xray_checksums), "legal/xray/CHECKSUMS.sha256"),
)

@Composable
private fun AppLanguageDialog(
    supportedLocales: List<Locale>,
    selectedLocale: Locale?,
    onDismiss: () -> Unit,
    onSelect: (Locale?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_app_language_dialog_title)) },
        text = {
            Column {
                SelectableOptionRow(
                    title = stringResource(R.string.settings_app_language_system_default),
                    description = null,
                    selected = selectedLocale == null,
                    onSelected = { onSelect(null) },
                )
                supportedLocales.forEach { locale ->
                    SelectableOptionRow(
                        title = locale.nativeDisplayName(),
                        description = null,
                        selected = locale == selectedLocale,
                        onSelected = { onSelect(locale) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

private fun Resources.loadSupportedAppLocales(): List<Locale> {
    val parser = getXml(R.xml.locales_config)
    return try {
        buildList {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "locale") {
                    parser.getAttributeValue(ANDROID_RESOURCE_NAMESPACE, "name")
                        ?.let(Locale::forLanguageTag)
                        ?.takeUnless { it.language.isEmpty() }
                        ?.let(::add)
                }
                event = parser.next()
            }
        }
    } finally {
        parser.close()
    }
}

private fun Locale.nativeDisplayName(): String = getDisplayName(this).replaceFirstChar { it.titlecase() }

private const val ANDROID_RESOURCE_NAMESPACE = "http://schemas.android.com/apk/res/android"

@Composable
private fun FieldStyleDialog(
    selected: NotificationStyle,
    onDismiss: () -> Unit,
    onSelect: (NotificationStyle) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_notification_field_style)) },
        text = {
            Column {
                NotificationStyle.entries.forEach { style ->
                    SelectableOptionRow(
                        title = stringResource(style.labelResource),
                        description = stringResource(style.descriptionResource),
                        selected = style == selected,
                        onSelected = {
                            onSelect(style)
                            onDismiss()
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) }
        },
    )
}

@Composable
private fun UpdateFrequencyDialog(
    currentValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(currentValue.toString()) }
    val parsed = text.toIntOrNull()
    val isValid = parsed != null &&
        parsed in NotificationSettings.MIN_UPDATE_INTERVAL_MS..NotificationSettings.MAX_UPDATE_INTERVAL_MS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_notification_update_frequency)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value.filter(Char::isDigit).take(4) },
                singleLine = true,
                isError = text.isNotEmpty() && !isValid,
                suffix = { Text(stringResource(R.string.settings_milliseconds_abbreviation)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(
                        stringResource(
                            R.string.settings_update_frequency_range,
                            NotificationSettings.MIN_UPDATE_INTERVAL_MS,
                            NotificationSettings.MAX_UPDATE_INTERVAL_MS,
                            NotificationSettings.DEFAULT_UPDATE_INTERVAL_MS,
                            stringResource(R.string.settings_milliseconds_abbreviation),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = isValid,
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun NotificationFieldsDialog(
    settings: NotificationSettings,
    onDismiss: () -> Unit,
    onFieldEnabledChange: (NotificationField, Boolean) -> Unit,
    onReorder: (List<NotificationField>) -> Unit,
) {
    val order = remember { settings.normalizedFieldOrder().toMutableStateList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_notification_fields_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_notification_fields_reorder_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                ReorderableFieldList(
                    order = order,
                    isEnabled = settings::isFieldEnabled,
                    onToggle = onFieldEnabledChange,
                    onReordered = { onReorder(order.toList()) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) }
        },
    )
}

@Composable
private fun ReorderableFieldList(
    order: SnapshotStateList<NotificationField>,
    isEnabled: (NotificationField) -> Boolean,
    onToggle: (NotificationField, Boolean) -> Unit,
    onReordered: () -> Unit,
) {
    var draggingField by remember { mutableStateOf<NotificationField?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<NotificationField, Int>() }

    Column(modifier = Modifier.fillMaxWidth()) {
        order.forEach { field ->
            key(field) {
                val dragging = field == draggingField
                SettingsSwitchRow(
                    title = stringResource(field.labelResource),
                    description = stringResource(field.descriptionResource),
                    checked = isEnabled(field),
                    onCheckedChange = { onToggle(field, it) },
                    modifier = Modifier
                        .onGloballyPositioned { heights[field] = it.size.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffsetY else 0f }
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            if (dragging) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                Color.Transparent
                            },
                        )
                        .heightIn(min = 52.dp),
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.DragIndicator,
                            contentDescription = stringResource(R.string.settings_drag_to_reorder),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .pointerInput(field) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggingField = field
                                            dragOffsetY = 0f
                                        },
                                        onDragEnd = {
                                            draggingField = null
                                            dragOffsetY = 0f
                                            onReordered()
                                        },
                                        onDragCancel = {
                                            draggingField = null
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount.y
                                            val current = order.indexOf(field)
                                            if (dragAmount.y < 0 && current > 0) {
                                                val above = order[current - 1]
                                                val threshold = (heights[above] ?: 0) / 2f
                                                if (-dragOffsetY > threshold) {
                                                    order.add(current - 1, order.removeAt(current))
                                                    dragOffsetY += (heights[above] ?: 0)
                                                }
                                            } else if (dragAmount.y > 0 && current < order.lastIndex) {
                                                val below = order[current + 1]
                                                val threshold = (heights[below] ?: 0) / 2f
                                                if (dragOffsetY > threshold) {
                                                    order.add(current + 1, order.removeAt(current))
                                                    dragOffsetY -= (heights[below] ?: 0)
                                                }
                                            }
                                        },
                                    )
                                },
                        )
                    },
                )
            }
        }
    }
}
