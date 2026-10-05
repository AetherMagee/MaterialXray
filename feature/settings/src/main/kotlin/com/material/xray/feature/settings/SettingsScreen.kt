package com.material.xray.feature.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.material.xray.core.android.locale.setAppLocales
import com.material.xray.core.data.repository.BackupSummary
import com.material.xray.core.data.repository.SettingsSnapshot
import com.material.xray.core.model.AppUpdateCheckStatus
import com.material.xray.core.model.AppUpdateInterval
import com.material.xray.core.model.ConnectionState
import com.material.xray.core.model.GeoDataUpdateInterval
import com.material.xray.core.model.Ipv6Mode
import com.material.xray.core.model.LauncherIcon
import com.material.xray.core.model.NotificationField
import com.material.xray.core.model.NotificationSettings
import com.material.xray.core.model.NotificationStyle
import com.material.xray.core.model.OtherVpnMode
import com.material.xray.core.model.RootConnectionBackend
import com.material.xray.core.model.RoutingPolicyControl
import com.material.xray.core.model.XrayLogLevel
import com.material.xray.core.model.XrayOutbound
import com.material.xray.core.model.XrayRuntimeSettings
import com.material.xray.core.model.ipv6DnsServers
import com.material.xray.core.model.isInProgress
import com.material.xray.core.runtime.GeoDataAsset
import com.material.xray.core.runtime.GeoDataDownloadProgress
import com.material.xray.core.runtime.OemAutostartGuidance
import com.material.xray.core.runtime.XrayCoreVersion
import com.material.xray.core.ui.R
import com.material.xray.core.ui.components.DropdownOption
import com.material.xray.core.ui.components.FadingOutlinedTextField as OutlinedTextField
import com.material.xray.core.ui.components.ReadOnlyDropdownField
import com.material.xray.core.ui.components.ScrolledTopAppBar
import com.material.xray.core.ui.components.SettingsSwitchRow
import com.material.xray.core.ui.components.TooltipIconButton
import com.material.xray.core.ui.components.UpdateChecksSetting
import com.material.xray.core.ui.components.UpdateIntervalDialog
import com.material.xray.core.ui.components.rememberSystemState
import com.material.xray.core.ui.text.descriptionResource
import com.material.xray.core.ui.text.dropdownDescriptionResource
import com.material.xray.core.ui.text.labelResource
import com.material.xray.core.xray.TproxyCompatibility
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collect
import org.koin.compose.viewmodel.koinViewModel
import org.xmlpull.v1.XmlPullParser

@Composable
fun SettingsScreen(
    showTitleBarLogo: Boolean,
    onOpenDnsSettings: () -> Unit,
    onOpenXrayCore: () -> Unit,
    xrayCorePage: OptionalSettingsPage? = null,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val loaded = settings
    if (loaded == null) {
        SettingsLoadingScreen(showTitleBarLogo)
        return
    }
    Box(modifier = Modifier.focusRestorer().focusGroup()) {
        SettingsScreenContent(
            viewModel = viewModel,
            settings = loaded,
            onOpenDnsSettings = onOpenDnsSettings,
            xrayCorePage = xrayCorePage,
            onOpenXrayCore = onOpenXrayCore,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("CyclomaticComplexMethod")
@Composable
private fun SettingsScreenContent(
    viewModel: SettingsViewModel,
    settings: SettingsSnapshot,
    onOpenDnsSettings: () -> Unit,
    xrayCorePage: OptionalSettingsPage?,
    onOpenXrayCore: () -> Unit,
) {
    val rootAvailable by viewModel.rootAvailable.collectAsStateWithLifecycle()
    val rootAccessChecking by viewModel.rootAccessChecking.collectAsStateWithLifecycle()
    val tproxyCompatibility by viewModel.tproxyCompatibility.collectAsStateWithLifecycle()
    val geoipUpdating by viewModel.geoipUpdating.collectAsStateWithLifecycle()
    val geositeUpdating by viewModel.geositeUpdating.collectAsStateWithLifecycle()
    val geoDataClearing by viewModel.geoDataClearing.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val ipv6SessionState by viewModel.ipv6SessionState.collectAsStateWithLifecycle()
    val geoDataDownloadProgress by viewModel.geoDataDownloadProgress.collectAsStateWithLifecycle()
    val geoDataLastUpdated by viewModel.geoDataLastUpdated.collectAsStateWithLifecycle()
    val geoDataCachedSizes by viewModel.geoDataCachedSizes.collectAsStateWithLifecycle()
    val xrayCoreVersion by viewModel.xrayCoreVersion.collectAsStateWithLifecycle()
    val appResetting by viewModel.appResetting.collectAsStateWithLifecycle()
    val backupBusy by viewModel.backupBusy.collectAsStateWithLifecycle()
    val backupImportSummary by viewModel.backupImportSummary.collectAsStateWithLifecycle()
    val appUpdateCheckStatus by viewModel.appUpdateCheckStatus.collectAsStateWithLifecycle()
    val oemAutostartGuidance by viewModel.oemAutostartGuidance.collectAsStateWithLifecycle()
    val tunName = settings.tunName
    val dnsServers = settings.dnsServers
    val domesticDnsServers = settings.domesticDnsServers
    val useRootService = settings.useRootService
    val rootConnectionBackend = settings.rootConnectionBackend
    val bypassLan = settings.bypassLan
    val ipv6Mode = settings.ipv6Mode
    val xrayBufferSizeKiB = settings.xrayBufferSizeKiB
    val tunMtu = settings.tunMtu
    val xrayMemoryRestartThresholdMiB = settings.xrayMemoryRestartThresholdMiB
    val passiveHealthMonitoringEnabled = settings.passiveHealthMonitoringEnabled
    val xrayLogLevel = settings.xrayLogLevel
    val geoDataOperationInProgress = geoipUpdating || geositeUpdating || geoDataClearing
    val geoDataClearingAllowed = canClearGeoData(connectionState) && !geoDataClearing
    val defaultOutbound = settings.defaultOutbound
    val launcherIcon = settings.launcherIcon
    val showTitleBarLogo = settings.showTitleBarLogo
    val floatingConnectButton = settings.floatingConnectButton
    val showAdvancedOptions = settings.showAdvancedOptions
    val routeMxrayTrafficThroughXray = settings.routeMxrayTrafficThroughXray
    val notificationSettings = settings.notificationSettings
    val subscriptionSendHardwareId = settings.subscriptionSendHardwareId
    val routingPolicyControl = settings.routingPolicyControl
    val geoipUrl = settings.geoipUrl
    val geositeUrl = settings.geositeUrl
    val geoDataUpdateIntervalHours = settings.geoDataUpdateIntervalHours
    val latencyCheckUrl = settings.latencyCheckUrl
    val sortOutboundsByLatency = settings.sortOutboundsByLatency
    val appUpdateChecksEnabled = settings.appUpdateChecksEnabled
    val diagnosticsEnabled = settings.diagnosticsEnabled
    val context = LocalContext.current
    val isTelevision = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    rememberSystemState { viewModel.refreshOemAutostartGuidance() }
    val scrollState = rememberLazyListState()
    var showRootAccessDeniedDialog by rememberSaveable { mutableStateOf(false) }
    var showNotificationFieldsDialog by rememberSaveable { mutableStateOf(false) }
    var showFieldStyleDialog by rememberSaveable { mutableStateOf(false) }
    var showUpdateFrequencyDialog by rememberSaveable { mutableStateOf(false) }
    var showResetAppDialog by rememberSaveable { mutableStateOf(false) }
    var showOpenSourceLicensesDialog by rememberSaveable { mutableStateOf(false) }
    var showAppUpdateIntervalDialog by rememberSaveable { mutableStateOf(false) }
    var geoDataToDelete by rememberSaveable { mutableStateOf<GeoDataAsset?>(null) }
    var showClearGeoDataDialog by rememberSaveable { mutableStateOf(false) }
    val rootServiceAvailable = rootAvailable != false
    val rootServiceActive = useRootService && rootAvailable == true
    val rootNotificationFields = rootServiceActive && !rememberSystemState(::isAlwaysOnVpnEnabled).value

    val editingTunName = rememberSaveable(tunName, saver = TextFieldState.Saver) { TextFieldState(tunName) }
    val editingXrayBufferSizeKiB = rememberSaveable(xrayBufferSizeKiB, saver = TextFieldState.Saver) { TextFieldState(xrayBufferSizeKiB.toString()) }
    val editingTunMtu = rememberSaveable(tunMtu, saver = TextFieldState.Saver) { TextFieldState(tunMtu.toString()) }
    val editingXrayMemoryRestartThresholdMiB = rememberSaveable(xrayMemoryRestartThresholdMiB, saver = TextFieldState.Saver) {
        TextFieldState(xrayMemoryRestartThresholdMiB.toString())
    }
    val editingGeoipUrl = rememberSaveable(geoipUrl, saver = TextFieldState.Saver) { TextFieldState(geoipUrl) }
    val editingGeositeUrl = rememberSaveable(geositeUrl, saver = TextFieldState.Saver) { TextFieldState(geositeUrl) }
    val editingGeoDataUpdateIntervalHours = rememberSaveable(geoDataUpdateIntervalHours, saver = TextFieldState.Saver) {
        TextFieldState(geoDataUpdateIntervalHours.toString())
    }
    val editingLatencyCheckUrl = rememberSaveable(latencyCheckUrl, saver = TextFieldState.Saver) { TextFieldState(latencyCheckUrl) }
    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val hasTunNameChanges by remember(editingTunName, tunName) { derivedStateOf { editingTunName.text.toString() != tunName } }
    val parsedXrayBufferSizeKiB by remember(editingXrayBufferSizeKiB) {
        derivedStateOf { editingXrayBufferSizeKiB.text.toString().toIntOrNull() }
    }
    val parsedTunMtu by remember(editingTunMtu) { derivedStateOf { editingTunMtu.text.toString().toIntOrNull() } }
    val parsedXrayMemoryRestartThresholdMiB by remember(editingXrayMemoryRestartThresholdMiB) {
        derivedStateOf { editingXrayMemoryRestartThresholdMiB.text.toString().toIntOrNull() }
    }
    val parsedGeoDataUpdateIntervalHours by remember(editingGeoDataUpdateIntervalHours) {
        derivedStateOf { editingGeoDataUpdateIntervalHours.text.toString().toIntOrNull() }
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
        derivedStateOf { editingXrayBufferSizeKiB.text.toString() != xrayBufferSizeKiB.toString() }
    }
    val hasTunMtuChanges by remember(editingTunMtu, tunMtu) {
        derivedStateOf { editingTunMtu.text.toString() != tunMtu.toString() }
    }
    val hasXrayMemoryRestartThresholdMiBChanges by remember(
        editingXrayMemoryRestartThresholdMiB,
        xrayMemoryRestartThresholdMiB,
    ) {
        derivedStateOf { editingXrayMemoryRestartThresholdMiB.text.toString() != xrayMemoryRestartThresholdMiB.toString() }
    }
    val hasGeoipUrlChanges by remember(editingGeoipUrl, geoipUrl) {
        derivedStateOf { editingGeoipUrl.text.toString().trim() != geoipUrl }
    }
    val hasGeositeUrlChanges by remember(editingGeositeUrl, geositeUrl) {
        derivedStateOf { editingGeositeUrl.text.toString().trim() != geositeUrl }
    }
    val hasGeoDataUpdateIntervalHoursChanges by remember(
        editingGeoDataUpdateIntervalHours,
        geoDataUpdateIntervalHours,
    ) {
        derivedStateOf { editingGeoDataUpdateIntervalHours.text.toString() != geoDataUpdateIntervalHours.toString() }
    }
    val hasLatencyCheckUrlChanges by remember(editingLatencyCheckUrl, latencyCheckUrl) {
        derivedStateOf { editingLatencyCheckUrl.text.toString().trim() != latencyCheckUrl }
    }
    val xrayCoreVersionText = xrayCoreVersionText(xrayCoreVersion)
    val appUpdateCheckInProgress = appUpdateCheckStatus?.isInProgress == true
    val appUpdateCheckDescription = appUpdateCheckStatus?.let { appUpdateCheckDescription(it) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.prepareBackupImport(it) } }

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
            viewModel.appResetFailures.collect {
                Toast.makeText(
                    context,
                    resources.getString(R.string.settings_app_reset_failed),
                    Toast.LENGTH_SHORT,
                ).show()
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "service") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsServiceSection(
                        settings = settings,
                        rootAvailable = rootAvailable,
                        rootAccessChecking = rootAccessChecking,
                        rootServiceAvailable = rootServiceAvailable,
                        rootServiceActive = rootServiceActive,
                        tproxyCompatibility = tproxyCompatibility,
                        oemAutostartGuidance = oemAutostartGuidance,
                        rootTunNameSetting = {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                RootTunNameSetting(
                                    visible = true,
                                    editingTunName = editingTunName,
                                    hasTunNameChanges = hasTunNameChanges,
                                    onSave = { viewModel.setTunName(editingTunName.text.toString()) },
                                )
                            }
                        },
                        actions = SettingsServiceActions(
                            onUseRootServiceChange = viewModel::setUseRootService,
                            onRetryRootAccess = viewModel::retryRootAccess,
                            onRootConnectionBackendChange = viewModel::setRootConnectionBackend,
                            onTunnelTetheredClientsChange = viewModel::setTunnelTetheredClients,
                            onOtherVpnModeChange = viewModel::setOtherVpnMode,
                            onRetryTproxyCompatibility = viewModel::retryTproxyCompatibilityCheck,
                            onAutoConnectChange = viewModel::setAutoConnect,
                            onOpenOemAutostartSettings = viewModel::openOemAutostartSettings,
                        ),
                    )
                }
            }

            item(key = "appearance_header") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HorizontalDivider()
                    Text(stringResource(R.string.settings_section_appearance), style = MaterialTheme.typography.titleMedium)
                }
            }
            item(key = "appearance") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLanguageSetting()

                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_show_title_bar_logo),
                        checked = showTitleBarLogo,
                        onCheckedChange = viewModel::setShowTitleBarLogo,
                    )

                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_floating_connect_button_title),
                        description = stringResource(R.string.settings_floating_connect_button_description),
                        checked = floatingConnectButton,
                        onCheckedChange = viewModel::setFloatingConnectButton,
                    )

                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_sort_outbounds_by_latency_title),
                        description = stringResource(R.string.settings_sort_outbounds_by_latency_description),
                        checked = sortOutboundsByLatency,
                        onCheckedChange = viewModel::setSortOutboundsByLatency,
                    )

                    // TVs have no notification shade, so nobody would see the connection notification.
                    if (!isTelevision) {
                        NotificationSettingsSection(
                            settings = notificationSettings,
                            rootMode = rootNotificationFields,
                            onConfigureFields = { showNotificationFieldsDialog = true },
                            onConfigureStyle = { showFieldStyleDialog = true },
                            onConfigureFrequency = { showUpdateFrequencyDialog = true },
                        )
                    }

                    SettingsNestedSection(title = stringResource(R.string.settings_app_icon_title)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LauncherIcon.entries.forEach { icon ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .selectable(
                                            selected = icon == launcherIcon,
                                            role = Role.RadioButton,
                                            onClick = { viewModel.setLauncherIcon(icon) },
                                        )
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(icon.labelResource), style = MaterialTheme.typography.bodyLarge)
                                    }
                                    RadioButton(selected = icon == launcherIcon, onClick = null)
                                }
                            }
                        }
                    }
                }
            }

            item(key = "routing_header") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HorizontalDivider()
                    Text(stringResource(R.string.settings_section_routing), style = MaterialTheme.typography.titleMedium)
                }
            }

            item(key = "routing") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsNestedSection(title = stringResource(R.string.settings_connectivity_title)) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_bypass_lan_title),
                            description = stringResource(R.string.settings_bypass_lan_description),
                            checked = bypassLan,
                            onCheckedChange = { viewModel.setBypassLan(it) },
                        )

                        val ipv6Selectable = isIpv6SelectionEnabled(rootServiceActive, rootConnectionBackend, tproxyCompatibility)
                        ReadOnlyDropdownField(
                            label = stringResource(R.string.settings_ipv6_mode_label),
                            selectedText = stringResource(ipv6Mode.labelResource),
                            supportingText = listOfNotNull(
                                stringResource(ipv6Mode.descriptionResource),
                                ipv6SessionState
                                    ?.takeIf { ipv6Mode == Ipv6Mode.Auto && connectionState is ConnectionState.Connected }
                                    ?.let { stringResource(it.labelResource) },
                            ).joinToString("\n"),
                            options = Ipv6Mode.entries.map { mode ->
                                DropdownOption(
                                    value = mode,
                                    label = stringResource(mode.labelResource),
                                    description = stringResource(mode.descriptionResource),
                                    enabled = ipv6Selectable || mode == Ipv6Mode.Off,
                                )
                            },
                            onSelected = viewModel::setIpv6Mode,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )

                        // The resolver lists carry both address families, so this only happens on a
                        // hand-written list. Worth saying here, because the switch looks like it
                        // applies to DNS and in that state it cannot.
                        if (ipv6Mode != Ipv6Mode.Off && hasIpv4OnlyDnsServers(dnsServers, domesticDnsServers)) {
                            SettingsNotice(text = stringResource(R.string.settings_allow_ipv6_dns_ipv4_only))
                        }
                    }

                    SettingsNestedSection(title = stringResource(R.string.settings_routing_policy_title)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            RoutingPolicyControl.entries.forEach { policy ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .selectable(
                                            selected = policy == routingPolicyControl,
                                            role = Role.RadioButton,
                                            onClick = { viewModel.setRoutingPolicyControl(policy) },
                                        )
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(policy.labelResource), style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            stringResource(policy.descriptionResource),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    RadioButton(
                                        selected = policy == routingPolicyControl,
                                        onClick = null,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "core_header") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HorizontalDivider()
                    Text(stringResource(R.string.settings_section_core), style = MaterialTheme.typography.titleMedium)
                }
            }

            if (xrayCorePage != null) {
                item(key = "xray_core") {
                    SettingsActionRow(
                        title = stringResource(R.string.settings_xray_core_title),
                        subtitle = xrayCorePage.summary(),
                        navigates = true,
                        onClick = onOpenXrayCore,
                    )
                }
            }

            if (showAdvancedOptions) {
                item(key = "route_mxray_traffic") {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_route_mxray_traffic_title),
                        description = stringResource(R.string.settings_route_mxray_traffic_description),
                        checked = routeMxrayTrafficThroughXray,
                        onCheckedChange = viewModel::setRouteMxrayTrafficThroughXray,
                    )
                }
                item(key = "xray_buffer") {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        AdvancedIntegerSetting(
                            state = editingXrayBufferSizeKiB,
                            label = stringResource(R.string.settings_xray_buffer_size_label),
                            supportingText = stringResource(
                                R.string.settings_xray_buffer_size_supporting_text,
                                XrayRuntimeSettings.MIN_XRAY_BUFFER_SIZE_KIB,
                                XrayRuntimeSettings.MAX_XRAY_BUFFER_SIZE_KIB,
                                XrayRuntimeSettings.DEFAULT_XRAY_BUFFER_SIZE_KIB,
                            ),
                            suffix = stringResource(R.string.settings_kib_abbreviation),
                            isValid = isXrayBufferSizeKiBValid,
                            hasChanges = hasXrayBufferSizeKiBChanges,
                            onSave = { parsedXrayBufferSizeKiB?.let(viewModel::setXrayBufferSizeKiB) },
                        )
                    }
                }
                if (!rootServiceActive || rootConnectionBackend == RootConnectionBackend.Tun) {
                    item(key = "tun_mtu") {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            TunMtuSetting(
                                visible = true,
                                state = editingTunMtu,
                                isValid = isTunMtuValid,
                                hasChanges = hasTunMtuChanges,
                                onSave = { parsedTunMtu?.let(viewModel::setTunMtu) },
                            )
                        }
                    }
                }
                item(key = "memory_restart_threshold") {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        AdvancedIntegerSetting(
                            state = editingXrayMemoryRestartThresholdMiB,
                            label = stringResource(R.string.settings_xray_memory_restart_threshold_label),
                            supportingText = stringResource(
                                R.string.settings_xray_memory_restart_threshold_supporting_text,
                                XrayRuntimeSettings.MIN_XRAY_MEMORY_RESTART_THRESHOLD_MIB,
                                XrayRuntimeSettings.MAX_XRAY_MEMORY_RESTART_THRESHOLD_MIB,
                                XrayRuntimeSettings.DEFAULT_XRAY_MEMORY_RESTART_THRESHOLD_MIB,
                            ),
                            suffix = stringResource(R.string.settings_mib_abbreviation),
                            isValid = isXrayMemoryRestartThresholdMiBValid,
                            hasChanges = hasXrayMemoryRestartThresholdMiBChanges,
                            onSave = {
                                parsedXrayMemoryRestartThresholdMiB
                                    ?.let(viewModel::setXrayMemoryRestartThresholdMiB)
                            },
                        )
                    }
                }
                item(key = "passive_health_monitoring") {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_passive_health_monitoring_title),
                        description = stringResource(R.string.settings_passive_health_monitoring_description),
                        checked = passiveHealthMonitoringEnabled,
                        onCheckedChange = viewModel::setPassiveHealthMonitoringEnabled,
                    )
                }
                item(key = "default_outbound") {
                    ReadOnlyDropdownField(
                        label = stringResource(R.string.settings_default_outbound_label),
                        selectedText = stringResource(defaultOutbound.labelResource),
                        supportingText = stringResource(defaultOutbound.descriptionResource),
                        options = XrayOutbound.entries.map { outbound ->
                            DropdownOption(
                                value = outbound,
                                label = stringResource(outbound.labelResource),
                                description = stringResource(outbound.descriptionResource),
                            )
                        },
                        onSelected = viewModel::setDefaultOutbound,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            item(key = "dns") {
                SettingsActionRow(
                    title = stringResource(R.string.settings_dns_title),
                    subtitle = stringResource(R.string.settings_dns_row_subtitle),
                    navigates = true,
                    onClick = onOpenDnsSettings,
                )
            }

            if (showAdvancedOptions) {
                item(key = "log_level") {
                    ReadOnlyDropdownField(
                        label = stringResource(R.string.settings_xray_log_level_label),
                        selectedText = stringResource(xrayLogLevel.labelResource),
                        supportingText = stringResource(
                            R.string.settings_default_value,
                            stringResource(XrayLogLevel.default.labelResource),
                        ),
                        options = XrayLogLevel.entries.map { level ->
                            DropdownOption(value = level, label = stringResource(level.labelResource))
                        },
                        onSelected = viewModel::setXrayLogLevel,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            item(key = "geo_data_update_interval") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AdvancedIntegerSetting(
                        state = editingGeoDataUpdateIntervalHours,
                        label = stringResource(R.string.settings_geo_data_update_interval_label),
                        supportingText = stringResource(
                            R.string.settings_geo_data_update_interval_supporting_text,
                            GeoDataUpdateInterval.MIN_HOURS,
                            GeoDataUpdateInterval.MAX_HOURS,
                            GeoDataUpdateInterval.DEFAULT_HOURS,
                        ),
                        suffix = stringResource(R.string.settings_hours_abbreviation),
                        isValid = isGeoDataUpdateIntervalHoursValid,
                        hasChanges = hasGeoDataUpdateIntervalHoursChanges,
                        onSave = {
                            parsedGeoDataUpdateIntervalHours
                                ?.let(viewModel::setGeoDataUpdateIntervalHours)
                        },
                    )
                }
            }

            item(key = "geoip") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedTextField(
                        state = editingGeoipUrl,
                        label = { Text(stringResource(R.string.settings_geoip_url_label)) },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            if (geoipUpdating) {
                                GeoDataDownloadStatus(geoDataDownloadProgress[GeoDataAsset.GEOIP])
                            } else {
                                GeoDataSupportingText(
                                    description = stringResource(R.string.settings_geoip_url_supporting_text),
                                    lastUpdated = geoDataLastUpdated[GeoDataAsset.GEOIP].takeIf { showAdvancedOptions },
                                    enabled = !geoipUpdating && geoDataClearingAllowed,
                                    onClick = { geoDataToDelete = GeoDataAsset.GEOIP },
                                )
                            }
                        },
                        trailingIcon = {
                            Box {
                                TooltipIconButton(
                                    tooltip = stringResource(R.string.settings_update_geoip),
                                    onClick = { viewModel.updateGeoipAsset(editingGeoipUrl.text.toString()) },
                                    enabled = !geoipUpdating && !geoDataClearing,
                                ) {
                                    if (geoipUpdating) {
                                        val description = stringResource(R.string.settings_geoip_updating)
                                        GeoDataCircularProgress(
                                            progress = geoDataDownloadProgress[GeoDataAsset.GEOIP],
                                            description = description,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = stringResource(R.string.settings_update_geoip),
                                        )
                                    }
                                }
                            }
                        },
                    )
                    if (hasGeoipUrlChanges) {
                        Button(onClick = { viewModel.setGeoipUrl(editingGeoipUrl.text.toString()) }) {
                            Text(stringResource(R.string.settings_save))
                        }
                    }
                }
            }

            item(key = "geosite") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedTextField(
                        state = editingGeositeUrl,
                        label = { Text(stringResource(R.string.settings_geosite_url_label)) },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            if (geositeUpdating) {
                                GeoDataDownloadStatus(geoDataDownloadProgress[GeoDataAsset.GEOSITE])
                            } else {
                                GeoDataSupportingText(
                                    description = stringResource(R.string.settings_geosite_url_supporting_text),
                                    lastUpdated = geoDataLastUpdated[GeoDataAsset.GEOSITE].takeIf { showAdvancedOptions },
                                    enabled = !geositeUpdating && geoDataClearingAllowed,
                                    onClick = { geoDataToDelete = GeoDataAsset.GEOSITE },
                                )
                            }
                        },
                        trailingIcon = {
                            Box {
                                TooltipIconButton(
                                    tooltip = stringResource(R.string.settings_update_geosite),
                                    onClick = { viewModel.updateGeositeAsset(editingGeositeUrl.text.toString()) },
                                    enabled = !geositeUpdating && !geoDataClearing,
                                ) {
                                    if (geositeUpdating) {
                                        val description = stringResource(R.string.settings_geosite_updating)
                                        GeoDataCircularProgress(
                                            progress = geoDataDownloadProgress[GeoDataAsset.GEOSITE],
                                            description = description,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = stringResource(R.string.settings_update_geosite),
                                        )
                                    }
                                }
                            }
                        },
                    )
                    if (hasGeositeUrlChanges) {
                        Button(onClick = { viewModel.setGeositeUrl(editingGeositeUrl.text.toString()) }) {
                            Text(stringResource(R.string.settings_save))
                        }
                    }
                }
            }

            if (showAdvancedOptions) {
                item(key = "latency_check_url") {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        OutlinedTextField(
                            state = editingLatencyCheckUrl,
                            label = { Text(stringResource(R.string.settings_latency_check_url_label)) },
                            lineLimits = TextFieldLineLimits.SingleLine,
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text(stringResource(R.string.settings_latency_check_url_supporting_text)) },
                        )
                        if (hasLatencyCheckUrlChanges) {
                            Button(onClick = { viewModel.setLatencyCheckUrl(editingLatencyCheckUrl.text.toString()) }) {
                                Text(stringResource(R.string.settings_save))
                            }
                        }
                    }
                }
            }

            item(key = "data_header") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HorizontalDivider()
                    Text(stringResource(R.string.settings_section_data), style = MaterialTheme.typography.titleMedium)
                }
            }
            item(key = "data_actions") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = !backupBusy,
                        onClick = { exportLauncher.launch("material-xray-backup.json") },
                    ) {
                        Text(stringResource(R.string.settings_export))
                    }
                    OutlinedButton(
                        enabled = !backupBusy,
                        onClick = { importLauncher.launch(arrayOf("application/json")) },
                    ) {
                        Text(stringResource(R.string.settings_import))
                    }
                    if (showAdvancedOptions) {
                        OutlinedButton(
                            enabled = canClearGeoData(connectionState) && !geoDataOperationInProgress,
                            onClick = { showClearGeoDataDialog = true },
                        ) {
                            Text(
                                stringResource(
                                    if (geoDataClearing) R.string.settings_clearing_geodata else R.string.settings_clear_geodata,
                                ),
                            )
                        }
                    }
                }
            }
            if (showAdvancedOptions) {
                item(key = "app_reset") {
                    SettingsActionRow(
                        title = stringResource(R.string.settings_reset_app),
                        subtitle = stringResource(
                            if (appResetting) {
                                R.string.settings_resetting_app
                            } else {
                                R.string.settings_reset_app_description
                            },
                        ),
                        enabled = !appResetting,
                        onClick = { showResetAppDialog = true },
                        navigates = true,
                    )
                }
            }

            item(key = "settings_header") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HorizontalDivider()
                    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)
                }
            }
            item(key = "app_settings") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // A provider may pin its hardware ID policy onto the selected server; while
                    // that policy is active the toggle cannot be turned off from Settings.
                    val selectedSubscriptionRequiresHwid =
                        viewModel.selectedSubscriptionRequiresHwid.collectAsStateWithLifecycle().value
                    val hwidLockedBySubscription = selectedSubscriptionRequiresHwid && subscriptionSendHardwareId
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_send_hardware_id_title),
                        description = stringResource(
                            if (hwidLockedBySubscription) {
                                R.string.settings_send_hardware_id_locked
                            } else {
                                R.string.settings_send_hardware_id_description
                            },
                        ),
                        checked = subscriptionSendHardwareId,
                        onCheckedChange = viewModel::setSubscriptionSendHardwareId,
                        enabled = !hwidLockedBySubscription,
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_diagnostics_title),
                        description = stringResource(R.string.settings_diagnostics_description),
                        checked = diagnosticsEnabled,
                        onCheckedChange = viewModel::setDiagnosticsEnabled,
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_show_advanced_options),
                        checked = showAdvancedOptions,
                        onCheckedChange = viewModel::setShowAdvancedOptions,
                    )
                }
            }

            item(key = "about_header") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HorizontalDivider()
                    Text(stringResource(R.string.settings_section_about), style = MaterialTheme.typography.titleMedium)
                }
            }
            item(key = "update_checks") {
                UpdateChecksSetting(
                    title = stringResource(R.string.settings_app_update_checks_title),
                    checked = appUpdateChecksEnabled,
                    description = stringResource(settings.appUpdateInterval.descriptionResource),
                    onClick = { showAppUpdateIntervalDialog = true },
                    onCheckedChange = viewModel::setAppUpdateChecksEnabled,
                )
            }
            item(key = "check_for_updates") {
                SettingsActionRow(
                    title = stringResource(R.string.settings_check_for_updates),
                    subtitle = appUpdateCheckDescription,
                    enabled = !appUpdateCheckInProgress,
                    inProgress = appUpdateCheckInProgress,
                    onClick = viewModel::checkForAppUpdate,
                )
            }
            item(key = "licenses") {
                SettingsActionRow(
                    title = stringResource(R.string.settings_open_source_licenses),
                    subtitle = stringResource(R.string.settings_open_source_licenses_description),
                    onClick = { showOpenSourceLicensesDialog = true },
                )
            }
            item(key = "app_version") {
                val appVersion = remember(context) {
                    runCatching {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull()
                }
                Text(
                    text = if (appVersion == null) {
                        stringResource(R.string.settings_app_version_unknown, stringResource(R.string.app_name))
                    } else {
                        stringResource(R.string.settings_app_version, stringResource(R.string.app_name), appVersion)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item(key = "xray_version") {
                Text(
                    xrayCoreVersionText,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    SettingsDialogs(
        showRootAccessDeniedDialog = showRootAccessDeniedDialog,
        showNotificationFieldsDialog = showNotificationFieldsDialog,
        showUpdateFrequencyDialog = showUpdateFrequencyDialog,
        showFieldStyleDialog = showFieldStyleDialog,
        showResetAppDialog = showResetAppDialog,
        backupImportSummary = backupImportSummary,
        backupBusy = backupBusy,
        notificationSettings = notificationSettings,
        rootMode = rootNotificationFields,
        actions = SettingsDialogActions(
            onDismissRootAccessDenied = { showRootAccessDeniedDialog = false },
            onDismissNotificationFields = { showNotificationFieldsDialog = false },
            onDismissUpdateFrequency = { showUpdateFrequencyDialog = false },
            onDismissFieldStyle = { showFieldStyleDialog = false },
            onDismissResetApp = { showResetAppDialog = false },
            onResetApp = {
                showResetAppDialog = false
                viewModel.resetApp()
            },
            onDismissBackupImport = viewModel::dismissBackupImport,
            onConfirmBackupImport = viewModel::confirmBackupImport,
            onFieldEnabledChange = viewModel::setNotificationFieldEnabled,
            onReorderFields = viewModel::setNotificationFieldOrder,
            onUpdateFrequency = viewModel::setNotificationUpdateIntervalMs,
            onSelectFieldStyle = viewModel::setNotificationStyle,
        ),
    )
    if (showOpenSourceLicensesDialog) {
        OpenSourceLicensesDialog(onDismiss = { showOpenSourceLicensesDialog = false })
    }
    if (showAppUpdateIntervalDialog) {
        UpdateIntervalDialog(
            current = settings.appUpdateInterval,
            default = AppUpdateInterval.default,
            options = AppUpdateInterval.entries.map { DropdownOption(it, stringResource(it.labelResource)) },
            onDismiss = { showAppUpdateIntervalDialog = false },
            onConfirm = {
                viewModel.setAppUpdateInterval(it)
                showAppUpdateIntervalDialog = false
            },
        )
    }
    geoDataToDelete?.let { asset ->
        DeleteGeoDataDialog(
            asset = asset,
            bytes = geoDataCachedSizes[asset] ?: 0L,
            enabled = geoDataClearingAllowed && !(if (asset == GeoDataAsset.GEOIP) geoipUpdating else geositeUpdating),
            onDismiss = { geoDataToDelete = null },
            onConfirm = {
                viewModel.clearGeoData(asset)
                geoDataToDelete = null
            },
        )
    }
    if (showClearGeoDataDialog) {
        DeleteGeoDataDialog(
            asset = null,
            bytes = geoDataCachedSizes.values.sum(),
            enabled = canClearGeoData(connectionState) && !geoDataOperationInProgress,
            onDismiss = { showClearGeoDataDialog = false },
            onConfirm = {
                viewModel.clearGeoData()
                showClearGeoDataDialog = false
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
            modifier = Modifier.fillMaxSize().padding(padding),
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

/** What the service section's controls change. */
private data class SettingsServiceActions(
    val onUseRootServiceChange: (Boolean) -> Unit,
    val onRetryRootAccess: () -> Unit,
    val onRootConnectionBackendChange: (RootConnectionBackend) -> Unit,
    val onTunnelTetheredClientsChange: (Boolean) -> Unit,
    val onOtherVpnModeChange: (OtherVpnMode) -> Unit,
    val onRetryTproxyCompatibility: () -> Unit,
    val onAutoConnectChange: (Boolean) -> Unit,
    val onOpenOemAutostartSettings: () -> Unit,
)

@Composable
private fun SettingsServiceSection(
    settings: SettingsSnapshot,
    rootAvailable: Boolean?,
    rootAccessChecking: Boolean,
    rootServiceAvailable: Boolean,
    rootServiceActive: Boolean,
    tproxyCompatibility: TproxyCompatibility,
    oemAutostartGuidance: OemAutostartGuidance,
    rootTunNameSetting: @Composable () -> Unit,
    actions: SettingsServiceActions,
) {
    Text(
        text = stringResource(R.string.settings_section_service),
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.titleMedium,
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (rootAvailable == false) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_use_root_service), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.settings_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TooltipIconButton(
                    tooltip = stringResource(R.string.settings_retry_root_access),
                    onClick = actions.onRetryRootAccess,
                    enabled = !rootAccessChecking,
                ) {
                    if (rootAccessChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.settings_retry_root_access))
                    }
                }
            }
        } else {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_use_root_service),
                checked = settings.useRootService,
                onCheckedChange = actions.onUseRootServiceChange,
                enabled = rootServiceAvailable && !rootAccessChecking,
            )
        }

        if (rootServiceActive) {
            val tproxySelectable = tproxyCompatibility !is TproxyCompatibility.Unsupported
            val supportingText = tproxyCompatibilitySupportingText(tproxyCompatibility)
            SettingsNestedSection(title = stringResource(R.string.settings_root_connection_backend)) {
                RootConnectionBackend.entries.forEach { backend ->
                    SettingsRadioRow(
                        title = stringResource(backend.labelResource),
                        description = stringResource(backend.descriptionResource),
                        selected = backend == settings.rootConnectionBackend,
                        enabled = backend == RootConnectionBackend.Tun || tproxySelectable,
                        onClick = { actions.onRootConnectionBackendChange(backend) },
                    )
                }
                supportingText?.let { text ->
                    Text(
                        text,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (tproxyCompatibility is TproxyCompatibility.Unsupported) {
                TextButton(
                    onClick = actions.onRetryTproxyCompatibility,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.settings_retry_compatibility_check))
                }
            }

            SettingsSwitchRow(
                title = stringResource(R.string.settings_tunnel_tethered_clients_title),
                description = stringResource(R.string.settings_tunnel_tethered_clients_description),
                checked = settings.tunnelTetheredClients,
                onCheckedChange = actions.onTunnelTetheredClientsChange,
            )

            if (settings.showAdvancedOptions && settings.rootConnectionBackend == RootConnectionBackend.Tproxy) {
                ReadOnlyDropdownField(
                    label = stringResource(R.string.settings_other_vpn_mode),
                    selectedText = stringResource(settings.otherVpnMode.labelResource),
                    supportingText = stringResource(settings.otherVpnMode.descriptionResource),
                    options = OtherVpnMode.entries.map { mode ->
                        DropdownOption(
                            value = mode,
                            label = stringResource(mode.labelResource),
                            description = stringResource(mode.dropdownDescriptionResource),
                        )
                    },
                    onSelected = actions.onOtherVpnModeChange,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else if (settings.showAdvancedOptions) {
                rootTunNameSetting()
            }
        }

        SettingsSwitchRow(
            title = stringResource(R.string.settings_auto_connect_on_boot),
            checked = settings.autoConnect,
            onCheckedChange = actions.onAutoConnectChange,
            enabled = !settings.useRootService || rootServiceActive,
        )
        AlwaysOnVpnSetting(rootServiceActive = rootServiceActive)
        if (settings.autoConnect && oemAutostartGuidance.required && !oemAutostartGuidance.granted) {
            OemAutostartBanner(
                directSettingsAvailable = oemAutostartGuidance.directSettingsAvailable,
                onOpenSettings = actions.onOpenOemAutostartSettings,
            )
        }
    }
}

@Composable
private fun SettingsRadioRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
    }
}

/**
 * A note attached to the setting above it, for something the control itself cannot say.
 *
 * [action] is optional because some notes are only an explanation and have nothing to act on.
 */
@Composable
private fun SettingsNotice(
    text: String,
    action: @Composable (ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        action?.invoke(this)
    }
}

@Composable
private fun OemAutostartBanner(directSettingsAvailable: Boolean, onOpenSettings: () -> Unit) {
    SettingsNotice(
        text = stringResource(
            if (directSettingsAvailable) {
                R.string.settings_oem_autostart_required
            } else {
                R.string.settings_oem_autostart_external_required
            },
        ),
        action = {
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(
                    stringResource(
                        if (directSettingsAvailable) {
                            R.string.settings_open_autostart_settings
                        } else {
                            R.string.settings_open_app_settings
                        },
                    ),
                )
            }
        },
    )
}

@Composable
private fun tproxyCompatibilitySupportingText(compatibility: TproxyCompatibility): String? = when (compatibility) {
    TproxyCompatibility.Unknown,
    TproxyCompatibility.Checking,
    -> null
    is TproxyCompatibility.Supported -> null
    is TproxyCompatibility.Unsupported -> stringResource(R.string.settings_tproxy_unsupported)
}

/** Whether IPv6 is allowed but no configured resolver can be reached over it. */
internal fun hasIpv4OnlyDnsServers(dnsServers: String, domesticDnsServers: String): Boolean {
    val lists = listOf(dnsServers, domesticDnsServers)
    // An empty list hands that lookup to the OS resolver, which a dual-stack network may well have
    // given an IPv6 address. That is an unknown rather than an absence, so there is nothing to claim.
    if (lists.any(String::isBlank)) return false
    return lists.none { ipv6DnsServers(it).isNotEmpty() }
}

@Composable
private fun SettingsNestedSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun NotificationSettingsSection(
    settings: NotificationSettings,
    rootMode: Boolean,
    onConfigureFields: () -> Unit,
    onConfigureStyle: () -> Unit,
    onConfigureFrequency: () -> Unit,
) {
    val context = LocalContext.current
    var showAccessDialog by remember { mutableStateOf(false) }
    val accessState = rememberSystemState { notificationAccess(it) }
    val access = accessState.value
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        context.recordNotificationPermissionRequest()
        accessState.refresh()
    }

    SettingsNestedSection(title = stringResource(R.string.settings_notification_title)) {
        if (access != NotificationAccess.Available) {
            SettingsActionRow(
                title = stringResource(R.string.settings_notification_permission_unavailable),
                subtitle = stringResource(R.string.settings_notification_permission_unavailable_description),
                onClick = { showAccessDialog = true },
            )
        }

        SettingsActionRow(
            title = stringResource(R.string.settings_configure_notification_fields),
            subtitle = notificationFieldSummary(settings, rootMode),
            onClick = onConfigureFields,
        )
        SettingsActionRow(
            title = stringResource(R.string.settings_notification_field_style),
            subtitle = stringResource(settings.style.labelResource),
            onClick = onConfigureStyle,
        )
        SettingsActionRow(
            title = stringResource(R.string.settings_notification_update_frequency),
            subtitle = pluralStringResource(
                R.plurals.settings_notification_update_frequency_summary,
                settings.updateIntervalMs,
                settings.updateIntervalMs,
            ),
            onClick = onConfigureFrequency,
        )
    }

    if (showAccessDialog) {
        AlertDialog(
            onDismissRequest = { showAccessDialog = false },
            title = { Text(stringResource(R.string.settings_notification_permission_title)) },
            text = { Text(stringResource(R.string.settings_notification_permission_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAccessDialog = false
                        when (access) {
                            NotificationAccess.Available -> Unit
                            NotificationAccess.Requestable,
                            NotificationAccess.Rationale,
                            -> permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

                            NotificationAccess.SystemSettings -> context.openNotificationSettings()
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (access == NotificationAccess.SystemSettings) {
                                R.string.settings_open_notification_settings
                            } else {
                                R.string.settings_allow_notifications
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

private fun notificationAccess(context: android.content.Context): NotificationAccess {
    val permissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val permissionGranted = !permissionRequired ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val activity = context as? Activity
    return resolveNotificationAccess(
        permissionRequired = permissionRequired,
        permissionGranted = permissionGranted,
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        shouldShowRationale = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS),
        permissionRequested = context.wasNotificationPermissionRequested(),
    )
}

internal fun resolveNotificationAccess(
    permissionRequired: Boolean,
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
    shouldShowRationale: Boolean,
    permissionRequested: Boolean,
): NotificationAccess = when {
    !permissionRequired || permissionGranted -> {
        if (notificationsEnabled) NotificationAccess.Available else NotificationAccess.SystemSettings
    }
    shouldShowRationale -> NotificationAccess.Rationale
    permissionRequested -> NotificationAccess.SystemSettings
    else -> NotificationAccess.Requestable
}

private fun android.content.Context.openNotificationSettings() {
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        startActivity(appDetails)
        return
    }
    val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    runCatching { startActivity(notificationSettings) }
        .onFailure { startActivity(appDetails) }
}

private fun android.content.Context.recordNotificationPermissionRequest() {
    getSharedPreferences(NOTIFICATION_PERMISSION_PREFS, android.content.Context.MODE_PRIVATE)
        .edit()
        .putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true)
        .apply()
}

private fun android.content.Context.wasNotificationPermissionRequested(): Boolean = getSharedPreferences(
    NOTIFICATION_PERMISSION_PREFS,
    android.content.Context.MODE_PRIVATE,
).getBoolean(NOTIFICATION_PERMISSION_REQUESTED, false)

internal enum class NotificationAccess {
    Available,
    Requestable,
    Rationale,
    SystemSettings,
}

private const val NOTIFICATION_PERMISSION_PREFS = "notification_permission"
private const val NOTIFICATION_PERMISSION_REQUESTED = "requested"

/** How the settings dialogs report their results. */
private data class SettingsDialogActions(
    val onDismissRootAccessDenied: () -> Unit,
    val onDismissNotificationFields: () -> Unit,
    val onDismissUpdateFrequency: () -> Unit,
    val onDismissFieldStyle: () -> Unit,
    val onDismissResetApp: () -> Unit,
    val onResetApp: () -> Unit,
    val onDismissBackupImport: () -> Unit,
    val onConfirmBackupImport: () -> Unit,
    val onFieldEnabledChange: (NotificationField, Boolean) -> Unit,
    val onReorderFields: (List<NotificationField>) -> Unit,
    val onUpdateFrequency: (Int) -> Unit,
    val onSelectFieldStyle: (NotificationStyle) -> Unit,
)

@Composable
private fun SettingsDialogs(
    showRootAccessDeniedDialog: Boolean,
    showNotificationFieldsDialog: Boolean,
    showUpdateFrequencyDialog: Boolean,
    showFieldStyleDialog: Boolean,
    showResetAppDialog: Boolean,
    backupImportSummary: BackupSummary?,
    backupBusy: Boolean,
    notificationSettings: NotificationSettings,
    rootMode: Boolean,
    actions: SettingsDialogActions,
) {
    if (backupImportSummary != null) {
        AlertDialog(
            onDismissRequest = actions.onDismissBackupImport,
            title = { Text(stringResource(R.string.settings_backup_import_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_backup_import_confirmation,
                        pluralStringResource(
                            R.plurals.settings_backup_subscription_count,
                            backupImportSummary.subscriptionCount,
                            backupImportSummary.subscriptionCount,
                        ),
                        pluralStringResource(
                            R.plurals.settings_backup_server_count,
                            backupImportSummary.serverCount,
                            backupImportSummary.serverCount,
                        ),
                        pluralStringResource(
                            R.plurals.settings_backup_app_route_count,
                            backupImportSummary.appRouteCount,
                            backupImportSummary.appRouteCount,
                        ),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !backupBusy,
                    onClick = actions.onConfirmBackupImport,
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
                    onClick = actions.onDismissBackupImport,
                ) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    if (showRootAccessDeniedDialog) {
        AlertDialog(
            onDismissRequest = actions.onDismissRootAccessDenied,
            text = { Text(stringResource(R.string.settings_root_access_denied)) },
            confirmButton = {
                Button(onClick = actions.onDismissRootAccessDenied) {
                    Text(stringResource(R.string.settings_ok))
                }
            },
        )
    }

    if (showNotificationFieldsDialog) {
        NotificationFieldsDialog(
            settings = notificationSettings,
            rootMode = rootMode,
            onDismiss = actions.onDismissNotificationFields,
            onFieldEnabledChange = actions.onFieldEnabledChange,
            onReorder = actions.onReorderFields,
        )
    }

    if (showUpdateFrequencyDialog) {
        UpdateFrequencyDialog(
            currentValue = notificationSettings.updateIntervalMs,
            onDismiss = actions.onDismissUpdateFrequency,
            onConfirm = {
                actions.onUpdateFrequency(it)
                actions.onDismissUpdateFrequency()
            },
        )
    }

    if (showFieldStyleDialog) {
        FieldStyleDialog(
            selected = notificationSettings.style,
            onDismiss = actions.onDismissFieldStyle,
            onSelect = actions.onSelectFieldStyle,
        )
    }

    if (showResetAppDialog) {
        AlertDialog(
            onDismissRequest = actions.onDismissResetApp,
            title = { Text(stringResource(R.string.settings_reset_app_title)) },
            text = { Text(stringResource(R.string.settings_reset_app_confirmation)) },
            confirmButton = {
                TextButton(onClick = actions.onResetApp) {
                    Text(
                        text = stringResource(R.string.settings_reset),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onDismissResetApp) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun xrayCoreVersionText(state: XrayCoreVersion): String {
    val version = state.version
    return when {
        !state.loaded -> stringResource(R.string.settings_xray_core_version_detecting)
        version == null -> stringResource(R.string.settings_xray_core_version_unknown)
        else -> stringResource(R.string.settings_xray_core_version, version)
    }
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
private fun RootTunNameSetting(
    visible: Boolean,
    editingTunName: TextFieldState,
    hasTunNameChanges: Boolean,
    onSave: () -> Unit,
) {
    if (!visible) return

    OutlinedTextField(
        state = editingTunName,
        label = { Text(stringResource(R.string.settings_tun_interface_name_label)) },
        lineLimits = TextFieldLineLimits.SingleLine,
        modifier = Modifier.fillMaxWidth(),
        supportingText = { Text(stringResource(R.string.settings_tun_interface_name_automatic)) },
    )
    if (hasTunNameChanges) {
        Button(onClick = onSave) { Text(stringResource(R.string.settings_save)) }
    }
}

@Composable
private fun TunMtuSetting(
    visible: Boolean,
    state: TextFieldState,
    isValid: Boolean,
    hasChanges: Boolean,
    onSave: () -> Unit,
) {
    if (!visible) return
    AdvancedIntegerSetting(
        state = state,
        label = stringResource(R.string.settings_tun_mtu_label),
        supportingText = stringResource(
            R.string.settings_tun_mtu_supporting_text,
            XrayRuntimeSettings.MIN_TUN_MTU,
            XrayRuntimeSettings.MAX_TUN_MTU,
            XrayRuntimeSettings.DEFAULT_TUN_MTU,
        ),
        suffix = stringResource(R.string.settings_bytes_abbreviation),
        isValid = isValid,
        hasChanges = hasChanges,
        onSave = onSave,
    )
}

@Composable
private fun AdvancedIntegerSetting(
    state: TextFieldState,
    label: String,
    supportingText: String,
    suffix: String,
    isValid: Boolean,
    hasChanges: Boolean,
    onSave: () -> Unit,
) {
    OutlinedTextField(
        state = state,
        label = { Text(label) },
        supportingText = { Text(supportingText) },
        suffix = { Text(suffix) },
        isError = state.text.isNotEmpty() && !isValid,
        inputTransformation = digitsOnly(maxLength = 5),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        lineLimits = TextFieldLineLimits.SingleLine,
        modifier = Modifier.fillMaxWidth(),
    )
    if (hasChanges) {
        Button(onClick = onSave, enabled = isValid) {
            Text(stringResource(R.string.settings_save))
        }
    }
}

@Composable
private fun GeoDataSupportingText(description: String, lastUpdated: Long?, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val updatedText = lastUpdated?.let { timestamp ->
        val date = Date(timestamp)
        stringResource(
            R.string.settings_geo_data_last_updated,
            DateFormat.getLongDateFormat(context).format(date),
            DateFormat.format("HH:mm:ss", date),
        )
    }
    val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
    Text(
        buildAnnotatedString {
            append(description)
            if (updatedText != null) {
                append("\n\n")
                withLink(LinkAnnotation.Clickable("delete_asset", TextLinkStyles(style = linkStyle)) { if (enabled) onClick() }) {
                    append(updatedText)
                }
            }
        },
    )
}

@Composable
private fun DeleteGeoDataDialog(
    asset: GeoDataAsset?,
    bytes: Long,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val size = Formatter.formatShortFileSize(LocalContext.current, bytes)
    val message = stringResource(R.string.settings_delete_geo_asset_message, size)
    val sizeStart = message.indexOf(size)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (asset == null) {
                    stringResource(R.string.settings_delete_all_geo_assets_title)
                } else {
                    stringResource(R.string.settings_delete_geo_asset_title, asset.displayName)
                },
            )
        },
        text = {
            Text(
                buildAnnotatedString {
                    append(message.substring(0, sizeStart))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(size) }
                    append(message.substring(sizeStart + size.length))
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text(stringResource(R.string.settings_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun GeoDataCircularProgress(progress: GeoDataDownloadProgress?, description: String) {
    val fraction = progress?.fraction
    if (fraction == null) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp).semantics { contentDescription = description },
            strokeWidth = 2.dp,
        )
    } else {
        CircularProgressIndicator(
            progress = { fraction },
            modifier = Modifier.size(24.dp).semantics { contentDescription = description },
            strokeWidth = 2.dp,
        )
    }
}

@Composable
private fun GeoDataDownloadStatus(progress: GeoDataDownloadProgress?) {
    val context = LocalContext.current
    val totalBytes = progress?.totalBytes
    val text = when {
        progress == null -> stringResource(R.string.settings_updating)
        totalBytes != null && totalBytes > 0L -> stringResource(
            R.string.settings_geodata_download_progress_with_total,
            Formatter.formatShortFileSize(context, progress.bytesDownloaded),
            Formatter.formatShortFileSize(context, totalBytes),
        )
        else -> stringResource(
            R.string.settings_geodata_download_progress,
            Formatter.formatShortFileSize(context, progress.bytesDownloaded),
        )
    }
    Text(text)
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    inProgress: Boolean = false,
    navigates: Boolean = false,
) {
    val contentEnabled = enabled || inProgress
    val titleColor = if (contentEnabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val subtitleColor = if (contentEnabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = subtitleColor,
                )
            }
        }
        if (inProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        } else if (navigates) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = subtitleColor,
            )
        }
    }
}

@Composable
private fun appUpdateCheckDescription(status: AppUpdateCheckStatus): String = when (status) {
    AppUpdateCheckStatus.Starting -> stringResource(R.string.settings_update_check_starting)
    is AppUpdateCheckStatus.Fetching -> stringResource(R.string.settings_update_check_fetching, status.url)
    is AppUpdateCheckStatus.RetryingAfterHttpError -> stringResource(
        R.string.settings_update_check_retry_http,
        status.url,
        status.statusCode,
        status.nextUrl,
    )
    is AppUpdateCheckStatus.RetryingAfterConnectionFailure -> stringResource(
        R.string.settings_update_check_retry_connection,
        status.url,
        status.nextUrl,
    )
    is AppUpdateCheckStatus.RetryingAfterInvalidResponse -> stringResource(
        R.string.settings_update_check_retry_invalid_response,
        status.url,
        status.statusCode,
        status.nextUrl,
    )
    is AppUpdateCheckStatus.ReleaseReceived -> stringResource(
        R.string.settings_update_check_comparing,
        status.url,
        status.statusCode,
    )
    AppUpdateCheckStatus.UpToDate -> stringResource(R.string.settings_update_check_up_to_date)
    is AppUpdateCheckStatus.UpdateAvailable -> stringResource(
        R.string.settings_update_check_available,
        status.version,
    )
    AppUpdateCheckStatus.Failed -> stringResource(R.string.settings_update_check_failed)
}

@Composable
private fun AppLanguageSetting() {
    val context = LocalContext.current
    val resources = LocalResources.current
    var showDialog by remember { mutableStateOf(false) }
    val supportedLocales = remember(resources) { resources.loadSupportedAppLocales() }
    val selectedLocale = AppCompatDelegate.getApplicationLocales()[0]
    val selectedLanguageName = selectedLocale?.nativeDisplayName()
        ?: stringResource(R.string.settings_app_language_system_default)

    SettingsActionRow(
        title = stringResource(R.string.settings_app_language_title),
        subtitle = selectedLanguageName,
        navigates = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APP_LOCALE_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            } else {
                showDialog = true
            }
        },
    )

    if (showDialog) {
        AppLanguageDialog(
            supportedLocales = supportedLocales,
            selectedLocale = selectedLocale,
            onDismiss = { showDialog = false },
            onSelect = { locale ->
                showDialog = false
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
                AppLanguageOption(
                    label = stringResource(R.string.settings_app_language_system_default),
                    selected = selectedLocale == null,
                    onClick = { onSelect(null) },
                )
                supportedLocales.forEach { locale ->
                    AppLanguageOption(
                        label = locale.nativeDisplayName(),
                        selected = locale == selectedLocale,
                        onClick = { onSelect(locale) },
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

@Composable
private fun AppLanguageOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelect(style)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = style == selected,
                            onClick = {
                                onSelect(style)
                                onDismiss()
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(style.labelResource), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(style.descriptionResource),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
    val interval = rememberTextFieldState(currentValue.toString())
    val parsed = interval.text.toString().toIntOrNull()
    val isValid = parsed != null &&
        parsed in NotificationSettings.MIN_UPDATE_INTERVAL_MS..NotificationSettings.MAX_UPDATE_INTERVAL_MS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_notification_update_frequency)) },
        text = {
            OutlinedTextField(
                state = interval,
                inputTransformation = digitsOnly(maxLength = 4),
                lineLimits = TextFieldLineLimits.SingleLine,
                isError = interval.text.isNotEmpty() && !isValid,
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
    rootMode: Boolean,
    onDismiss: () -> Unit,
    onFieldEnabledChange: (NotificationField, Boolean) -> Unit,
    onReorder: (List<NotificationField>) -> Unit,
) {
    val order = remember(rootMode) { settings.normalizedFieldOrder(rootMode).toMutableStateList() }

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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { heights[field] = it.size.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffsetY else 0f }
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (dragging) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                Color.Transparent
                            },
                        )
                        .heightIn(min = 52.dp)
                        .toggleable(
                            value = isEnabled(field),
                            role = Role.Switch,
                            onValueChange = { onToggle(field, it) },
                        )
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(field.labelResource), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(field.descriptionResource),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = isEnabled(field),
                        onCheckedChange = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun notificationFieldSummary(settings: NotificationSettings, rootMode: Boolean): String {
    val enabledFields = settings.normalizedFieldOrder(rootMode)
        .filter(settings::isFieldEnabled)
        .map { stringResource(it.labelResource) }
    return if (enabledFields.isEmpty()) {
        stringResource(R.string.settings_no_custom_notification_fields)
    } else {
        enabledFields.joinToString(stringResource(R.string.settings_notification_field_separator))
    }
}

private fun digitsOnly(maxLength: Int) = InputTransformation.byValue { _, proposed ->
    proposed.filter(Char::isDigit).take(maxLength)
}
