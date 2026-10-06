package com.material.xray.feature.xraycore

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.core.ui.R
import com.material.xray.core.ui.components.AnimatedOptionContent
import com.material.xray.core.ui.components.DropdownOption
import com.material.xray.core.ui.components.ReadOnlyDropdownField
import com.material.xray.core.ui.components.ScrolledTopAppBar
import com.material.xray.core.ui.components.SelectableOptionRow
import com.material.xray.core.ui.components.SettingsFieldSpacing
import com.material.xray.core.ui.components.SettingsItemSpacing
import com.material.xray.core.ui.components.TooltipIconButton
import com.material.xray.core.ui.components.UpdateChecksSetting
import com.material.xray.core.ui.components.UpdateIntervalDialog
import com.material.xray.core.xray.InstalledXrayCore
import com.material.xray.core.xray.XrayCoreSource
import com.material.xray.core.xraycore.MINIMUM_XRAY_VERSION
import com.material.xray.core.xraycore.XrayCoreFailure
import com.material.xray.core.xraycore.XrayCoreOperation
import com.material.xray.core.xraycore.XrayCoreRelease
import com.material.xray.core.xraycore.XrayCoreState
import com.material.xray.core.xraycore.XrayCoreUpdateAction
import com.material.xray.core.xraycore.XrayCoreUpdateInterval
import com.material.xray.core.xraycore.XrayCoreUpdateSettings
import com.material.xray.core.xraycore.compareXrayVersions
import com.material.xray.core.xraycore.isRecommendedXrayVersion
import com.material.xray.core.xraycore.normalizeXrayVersion
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Xray core subpage: one list of the built-in core, installed cores and upstream releases to
 * download. Like the DNS page it is drawn over the settings list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XrayCoreScreen(
    useRootService: Boolean,
    onBack: () -> Unit,
    viewModel: XrayCoreViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val releases by viewModel.releases.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val updateSettings by viewModel.updateSettings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    var showFileWarning by rememberSaveable { mutableStateOf(false) }
    var showUpdateIntervalDialog by rememberSaveable { mutableStateOf(false) }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::installFromFile)
    }
    // Checks still run without the permission, but neither outcome could be shown.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current

    val activity = LocalActivity.current
    LaunchedEffect(viewModel) { viewModel.loadReleasesIfNeeded() }
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.onScreenLeft() }
    }
    OperationResultSnackbar(result, snackbarHostState, onSelect = viewModel::select, onShown = viewModel::clearResult)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.navigationBars,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ScrolledTopAppBar(
                title = stringResource(R.string.settings_xray_core_title),
                scrollBehavior = scrollBehavior,
                showLogo = false,
                navigationIcon = {
                    TooltipIconButton(tooltip = stringResource(R.string.settings_dns_back), onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_dns_back))
                    }
                },
            )
        },
    ) { padding ->
        val loaded = releases as? XrayCoreReleasesState.Loaded
        val entries = coreEntries(state, loaded?.releases.orEmpty())
        val releasesNote = releasesNote(viewModel.canDownload, releases)
        // A page loaded from a focused "Show more" pushes the button off screen, or removes it on
        // the last page, so focus moves to the first new version instead of getting lost.
        val listState = rememberLazyListState()
        val firstNewFocus = remember { FocusRequester() }
        var showMoreFocused by remember { mutableStateOf(false) }
        var keysBeforeMore by remember { mutableStateOf<Set<String>?>(null) }
        val firstNewKey = keysBeforeMore?.let { before -> entries.firstOrNull { it.key !in before }?.key }
        LaunchedEffect(loaded?.loadingMore) {
            if (keysBeforeMore == null || loaded?.loadingMore == true) return@LaunchedEffect
            val index = entries.indexOfFirst { it.key == firstNewKey }
            if (index >= 0) {
                // The header item comes first.
                listState.scrollToItem(index + 1)
                withFrameNanos {}
                firstNewFocus.requestFocus()
            }
            keysBeforeMore = null
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            // The inset belongs to the scrolling content so rows can pass behind the gesture bar.
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection),
                top = 8.dp,
                end = padding.calculateEndPadding(layoutDirection),
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
        ) {
            item(key = "versions_header") {
                VersionsHeader(
                    loading = viewModel.canDownload && (releases == XrayCoreReleasesState.Loading || loaded?.refreshing == true),
                    canRefresh = viewModel.canDownload,
                    onRefresh = viewModel::loadReleases,
                )
            }
            val firstOld = entries.firstOrNull { it.isOld }
            items(entries, key = { it.key }) { entry ->
                if (entry === firstOld) OldVersionsSeparator()
                CoreEntryRow(
                    entry = entry,
                    state = state,
                    onSelect = viewModel::select,
                    onDelete = viewModel::delete,
                    onInstall = viewModel::install,
                    onCancel = viewModel::cancel,
                    modifier = if (entry.key == firstNewKey) Modifier.focusRequester(firstNewFocus) else Modifier,
                )
            }
            if (loaded?.hasMore == true && !loaded.refreshing) {
                item(key = "show_more") {
                    // Centred under the full-width rows, or a remote's Down skips it: focus search
                    // favours the candidate whose centre lines up. It stays enabled while loading so
                    // focus does not jump off it.
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        OutlinedButton(
                            onClick = {
                                if (showMoreFocused) keysBeforeMore = entries.mapTo(HashSet()) { it.key }
                                viewModel.loadMoreReleases()
                            },
                            modifier = Modifier.onFocusChanged { showMoreFocused = it.isFocused },
                        ) {
                            if (loaded.loadingMore) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_xray_core_releases_more))
                        }
                    }
                }
            }
            releasesNote?.let { (text, isError) ->
                item(key = "releases_note") { Note(text, isError) }
            }
            item(key = "install_file") {
                InstallFileRow(
                    verifying = state.operation == XrayCoreOperation.Verifying(null),
                    enabled = !state.isBusy,
                    onClick = { showFileWarning = true },
                )
            }
            if (viewModel.canDownload) {
                item(key = "updates") {
                    UpdateSettings(
                        settings = updateSettings,
                        onPeriodicChecksChange = { enabled ->
                            if (enabled && needsNotificationPermission(context)) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            viewModel.setPeriodicChecks(enabled)
                        },
                        onIntervalClick = { showUpdateIntervalDialog = true },
                        onActionSelected = viewModel::setUpdateAction,
                    )
                }
            }
        }
    }

    if (showUpdateIntervalDialog) {
        UpdateIntervalDialog(
            current = updateSettings.interval,
            default = XrayCoreUpdateInterval.default,
            options = XrayCoreUpdateInterval.entries.map { DropdownOption(it, stringResource(it.labelResource)) },
            onDismiss = { showUpdateIntervalDialog = false },
            onConfirm = {
                viewModel.setUpdateInterval(it)
                showUpdateIntervalDialog = false
            },
        )
    }
    if (showFileWarning) {
        AlertDialog(
            onDismissRequest = { showFileWarning = false },
            title = { Text(stringResource(R.string.settings_xray_core_file_warning_title)) },
            text = {
                Text(
                    stringResource(
                        if (useRootService) R.string.settings_xray_core_file_warning_root else R.string.settings_xray_core_file_warning,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFileWarning = false
                        fileLauncher.launch(arrayOf("*/*"))
                    },
                ) { Text(stringResource(R.string.settings_xray_core_choose_file)) }
            },
            dismissButton = {
                TextButton(onClick = { showFileWarning = false }) { Text(stringResource(R.string.settings_xray_core_cancel)) }
            },
        )
    }
}

/** The supporting text of the Settings row that opens this page. */
@Composable
fun xrayCoreSummary(viewModel: XrayCoreViewModel = koinViewModel()): String {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected = state.installed.firstOrNull { it.id == state.selectedId }
    val bundledVersion = state.bundledVersion
    return when {
        selected != null -> stringResource(R.string.settings_xray_core_summary_custom, normalizeXrayVersion(selected.version))
        bundledVersion != null -> stringResource(R.string.settings_xray_core_summary_built_in, normalizeXrayVersion(bundledVersion))
        !state.loaded -> stringResource(R.string.settings_xray_core_version_detecting)
        else -> stringResource(R.string.settings_xray_core_version_unknown)
    }
}

@Composable
private fun UpdateSettings(
    settings: XrayCoreUpdateSettings,
    onPeriodicChecksChange: (Boolean) -> Unit,
    onActionSelected: (XrayCoreUpdateAction) -> Unit,
    onIntervalClick: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 16.dp),
    ) {
        Text(
            stringResource(R.string.settings_xray_core_updates),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(SettingsItemSpacing))
        UpdateChecksSetting(
            title = stringResource(R.string.settings_xray_core_periodic_checks),
            checked = settings.periodicChecks,
            description = stringResource(settings.interval.descriptionResource),
            onClick = onIntervalClick,
            onCheckedChange = onPeriodicChecksChange,
        )
        AnimatedOptionContent(visible = settings.periodicChecks) {
            ReadOnlyDropdownField(
                label = stringResource(R.string.settings_xray_core_update_action),
                selectedText = updateActionLabel(settings.action),
                options = XrayCoreUpdateAction.entries.map { DropdownOption(it, updateActionLabel(it)) },
                onSelected = onActionSelected,
                modifier = Modifier.padding(start = 16.dp, top = SettingsFieldSpacing, end = 16.dp, bottom = SettingsFieldSpacing),
            )
        }
    }
}

@Composable
private fun updateActionLabel(action: XrayCoreUpdateAction): String = stringResource(
    when (action) {
        XrayCoreUpdateAction.Notify -> R.string.settings_xray_core_update_action_notify
        XrayCoreUpdateAction.Install -> R.string.settings_xray_core_update_action_install
    },
)

private fun needsNotificationPermission(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/** One version in the list: the built-in core, an installed core, or a release not on the device yet. */
private data class CoreEntry(
    val version: String?,
    val builtIn: Boolean = false,
    val installed: InstalledXrayCore? = null,
    val release: XrayCoreRelease? = null,
    val newer: Boolean = false,
    val publishedAt: String? = release?.publishedAt,
) {
    val key: String get() = installed?.id ?: release?.let { "release_${it.tag}" } ?: "built_in"
    val isOld: Boolean get() = !builtIn && version != null && !isRecommendedXrayVersion(version)
}

/**
 * Every core on the device plus the releases that are not, newest first. A release that is
 * installed, or is the built-in version, is listed once.
 */
private fun coreEntries(state: XrayCoreState, releases: List<XrayCoreRelease>): List<CoreEntry> {
    val deviceVersions = state.installed.map { it.version } + listOfNotNull(state.bundledVersion)
    val builtInRelease = state.bundledVersion?.let { bundled -> releases.firstOrNull { compareXrayVersions(it.tag, bundled) == 0 } }
    val onDevice = listOf(CoreEntry(state.bundledVersion, builtIn = true, publishedAt = builtInRelease?.publishedAt)) +
        state.installed.map { core -> CoreEntry(core.version, installed = core, release = releases.firstOrNull { it.tag == core.id }) }
    val remote = releases
        .filter { release ->
            state.installed.none { it.id == release.tag } &&
                state.bundledVersion?.let { compareXrayVersions(release.tag, it) == 0 } != true
        }
        .map { release ->
            CoreEntry(
                version = release.tag,
                release = release,
                newer = deviceVersions.all { (compareXrayVersions(release.tag, it) ?: 0) > 0 },
            )
        }
    return (onDevice + remote).sortedWith { left, right ->
        compareXrayVersions(right.version.orEmpty(), left.version.orEmpty())?.takeIf { it != 0 }
            ?: compareValues(right.builtIn, left.builtIn)
    }
}

@Composable
private fun releasesNote(canDownload: Boolean, releases: XrayCoreReleasesState): Pair<String, Boolean>? = when {
    !canDownload -> stringResource(R.string.settings_xray_core_releases_unavailable) to false
    releases is XrayCoreReleasesState.Failed -> failureText(releases.failure) to true
    releases is XrayCoreReleasesState.Loaded && releases.failure != null -> failureText(releases.failure) to true
    releases is XrayCoreReleasesState.Loaded && releases.releases.isEmpty() ->
        stringResource(R.string.settings_xray_core_releases_empty) to false
    else -> null
}

@Composable
private fun VersionsHeader(loading: Boolean, canRefresh: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_xray_core_versions),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        when {
            loading -> Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            canRefresh -> {
                val label = stringResource(R.string.settings_xray_core_releases_retry)
                TooltipIconButton(tooltip = label, onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = label)
                }
            }
        }
    }
}

@Composable
private fun CoreEntryRow(
    entry: CoreEntry,
    state: XrayCoreState,
    onSelect: (String?) -> Unit,
    onDelete: (String) -> Unit,
    onInstall: (XrayCoreRelease) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val installed = entry.installed
    val release = entry.release
    val selected = if (entry.builtIn) state.selectedId == null else installed != null && state.selectedId == installed.id
    val operation = state.operation
    val inProgress = release != null &&
        installed == null &&
        when (operation) {
            is XrayCoreOperation.Downloading -> operation.tag == release.tag
            is XrayCoreOperation.Verifying -> operation.tag == release.tag
            else -> false
        }
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectableOptionRow(
            selected = selected,
            onSelected = {
                when {
                    entry.builtIn -> onSelect(null)
                    installed != null -> onSelect(installed.id)
                    release != null && !state.isBusy -> onInstall(release)
                }
            },
            modifier = modifier.weight(1f),
        ) {
            Text(
                text = entry.version?.let(::versionLabel) ?: "—",
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            EntryStatus(entry)
        }
        when {
            inProgress -> DownloadProgressButton(operation, onCancel)
            installed != null && !selected -> {
                val label = stringResource(R.string.settings_xray_core_delete, versionLabel(installed.version))
                TooltipIconButton(tooltip = label, onClick = { onDelete(installed.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = label)
                }
            }
            release != null && installed == null -> {
                val label = stringResource(R.string.settings_xray_core_release_install, versionLabel(release.tag))
                TooltipIconButton(tooltip = label, onClick = { onInstall(release) }, enabled = !state.isBusy) {
                    Icon(Icons.Filled.Download, contentDescription = label)
                }
            }
        }
    }
}

@Composable
private fun EntryStatus(entry: CoreEntry) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val installed = entry.installed
        when {
            entry.builtIn -> StatusChip(
                stringResource(R.string.settings_xray_core_source_bundled),
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
            installed != null -> StatusChip(
                stringResource(
                    if (installed.source == XrayCoreSource.Release) {
                        R.string.settings_xray_core_source_release
                    } else {
                        R.string.settings_xray_core_source_file
                    },
                ),
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.isOld) {
            StatusChip(
                stringResource(R.string.settings_xray_core_old),
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        if (entry.newer) {
            StatusChip(
                stringResource(R.string.settings_xray_core_release_new),
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        entry.publishedAt?.let {
            Text(
                formatReleaseDate(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadProgressButton(operation: XrayCoreOperation?, onCancel: () -> Unit) {
    val download = operation as? XrayCoreOperation.Downloading
    val total = download?.total
    val label = stringResource(R.string.settings_xray_core_cancel)
    TooltipIconButton(tooltip = label, onClick = onCancel, enabled = download != null) {
        Box(contentAlignment = Alignment.Center) {
            if (download == null || total == null || download.downloaded == 0L) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
            } else {
                CircularProgressIndicator(
                    progress = { (download.downloaded.toFloat() / total).coerceAtMost(1f) },
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                )
            }
            if (download != null) Icon(Icons.Filled.Close, contentDescription = label, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun OldVersionsSeparator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.settings_xray_core_old_separator),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
    }
}

@Composable
private fun StatusChip(text: String, color: Color, contentColor: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = color, contentColor = contentColor) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun InstallFileRow(verifying: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (verifying) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(R.string.settings_xray_core_install_file), color = MaterialTheme.colorScheme.primary)
    }
}

/** Shows how an install ended, offering to switch to a core that was just installed. */
@Composable
private fun OperationResultSnackbar(
    operation: XrayCoreOperation?,
    snackbarHostState: SnackbarHostState,
    onSelect: (String) -> Unit,
    onShown: () -> Unit,
) {
    val message = when (operation) {
        is XrayCoreOperation.Installed -> stringResource(R.string.settings_xray_core_installed_result, versionLabel(operation.core.version))
        is XrayCoreOperation.Failed -> failureText(operation.failure)
        else -> null
    }
    val useLabel = stringResource(R.string.settings_xray_core_use)
    LaunchedEffect(operation) {
        if (message == null) return@LaunchedEffect
        val installed = (operation as? XrayCoreOperation.Installed)?.core
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = useLabel.takeIf { installed != null },
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed && installed != null) onSelect(installed.id)
        // Cleared only once shown: clearing changes the key and would cancel this effect.
        onShown()
    }
}

@Composable
private fun failureText(failure: XrayCoreFailure): String = when (failure) {
    XrayCoreFailure.Network -> stringResource(R.string.settings_xray_core_failed_network)
    XrayCoreFailure.RateLimited -> stringResource(R.string.settings_xray_core_failed_rate_limited)
    XrayCoreFailure.Integrity -> stringResource(R.string.settings_xray_core_failed_integrity)
    XrayCoreFailure.Unsupported -> stringResource(R.string.settings_xray_core_failed_unsupported)
    XrayCoreFailure.Broken -> stringResource(R.string.settings_xray_core_failed_broken)
    XrayCoreFailure.TooOld -> stringResource(R.string.settings_xray_core_failed_too_old, versionLabel(MINIMUM_XRAY_VERSION))
}

@Composable
private fun Note(text: String, isError: Boolean) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun versionLabel(version: String): String = "v${normalizeXrayVersion(version)}"

@Composable
private fun formatReleaseDate(publishedAt: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(publishedAt, locale) {
        runCatching {
            val parser = SimpleDateFormat(GITHUB_DATE_PATTERN, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
            DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(requireNotNull(parser.parse(publishedAt)))
        }.getOrDefault(publishedAt.take(ISO_DATE_LENGTH))
    }
}

private const val ISO_DATE_LENGTH = 10
private const val GITHUB_DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'"
