package com.material.xray.feature.settings

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.core.data.xraycore.MINIMUM_XRAY_VERSION
import com.material.xray.core.data.xraycore.XrayCoreFailure
import com.material.xray.core.data.xraycore.XrayCoreOperation
import com.material.xray.core.data.xraycore.XrayCoreRelease
import com.material.xray.core.data.xraycore.XrayCoreState
import com.material.xray.core.data.xraycore.compareXrayVersions
import com.material.xray.core.data.xraycore.normalizeXrayVersion
import com.material.xray.core.ui.R
import com.material.xray.core.ui.components.ScrolledTopAppBar
import com.material.xray.core.ui.components.SelectableOptionRow
import com.material.xray.core.ui.components.TooltipIconButton
import com.material.xray.core.xray.InstalledXrayCore
import com.material.xray.core.xray.XrayCoreSource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Xray core subpage: which core runs, the cores installed next to the bundled one, and
 * upstream releases to download. Like the DNS page it is drawn over the settings list.
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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    LaunchedEffect(viewModel) { viewModel.loadReleasesIfNeeded() }
    var showFileWarning by rememberSaveable { mutableStateOf(false) }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::installFromFile)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0.dp),
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "intro") {
                Text(
                    text = stringResource(R.string.settings_xray_core_intro),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.operation?.let { operation ->
                item(key = "operation") {
                    OperationCard(operation, onCancel = viewModel::cancel, onDismiss = viewModel::clearResult)
                }
            }
            item(key = "installed_header") { SectionHeader(stringResource(R.string.settings_xray_core_installed)) }
            item(key = "bundled") {
                SelectableOptionRow(
                    title = stringResource(R.string.settings_xray_core_bundled_title),
                    description = state.bundledVersion
                        ?.let { stringResource(R.string.settings_xray_core_bundled_description, it) }
                        ?: stringResource(R.string.settings_xray_core_bundled_description_unknown),
                    selected = state.selectedId == null,
                    onSelected = { viewModel.select(null) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(state.installed, key = { "installed_${it.id}" }) { core ->
                InstalledCoreRow(
                    core = core,
                    selected = state.selectedId == core.id,
                    onSelect = { viewModel.select(core.id) },
                    onDelete = { viewModel.delete(core.id) },
                )
            }
            item(key = "install_file") {
                ActionRow(
                    title = stringResource(R.string.settings_xray_core_install_file),
                    subtitle = stringResource(R.string.settings_xray_core_install_file_description),
                    enabled = !state.isBusy,
                    onClick = { showFileWarning = true },
                )
            }
            item(key = "releases_header") { SectionHeader(stringResource(R.string.settings_xray_core_releases)) }
            if (viewModel.canDownload) {
                releaseItems(state, releases, onRetry = viewModel::loadReleases, onInstall = viewModel::install)
            } else {
                item(key = "releases_unavailable") { Note(stringResource(R.string.settings_xray_core_releases_unavailable)) }
            }
        }
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

private fun androidx.compose.foundation.lazy.LazyListScope.releaseItems(
    state: XrayCoreState,
    releases: XrayCoreReleasesState,
    onRetry: () -> Unit,
    onInstall: (XrayCoreRelease) -> Unit,
) {
    when (releases) {
        XrayCoreReleasesState.Loading -> item(key = "releases_loading") {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.settings_xray_core_releases_loading), style = MaterialTheme.typography.bodyMedium)
            }
        }
        is XrayCoreReleasesState.Failed -> item(key = "releases_failed") {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(failureText(releases.failure), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.settings_xray_core_releases_retry)) }
            }
        }
        is XrayCoreReleasesState.Loaded -> if (releases.releases.isEmpty()) {
            item(key = "releases_empty") { Note(stringResource(R.string.settings_xray_core_releases_empty)) }
        } else {
            items(releases.releases, key = { "release_${it.tag}" }) { release ->
                ReleaseRow(
                    release = release,
                    installed = state.installed.any { it.id == release.tag },
                    newer = state.bundledVersion?.let { compareXrayVersions(release.tag, it)?.let { comparison -> comparison > 0 } } == true,
                    enabled = !state.isBusy,
                    onInstall = { onInstall(release) },
                )
            }
        }
    }
}

@Composable
private fun InstalledCoreRow(core: InstalledXrayCore, selected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectableOptionRow(
            title = "v${normalizeXrayVersion(core.version)}",
            description = when (core.source) {
                XrayCoreSource.Release -> stringResource(R.string.settings_xray_core_source_release)
                XrayCoreSource.File -> stringResource(R.string.settings_xray_core_source_file, core.sha256.take(SHA256_PREVIEW_CHARS))
            },
            selected = selected,
            onSelected = onSelect,
            modifier = Modifier.weight(1f),
        )
        if (!selected) {
            val label = stringResource(R.string.settings_xray_core_delete, normalizeXrayVersion(core.version))
            TooltipIconButton(tooltip = label, onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = label)
            }
        }
    }
}

@Composable
private fun ReleaseRow(release: XrayCoreRelease, installed: Boolean, newer: Boolean, enabled: Boolean, onInstall: () -> Unit) {
    val details = buildList {
        add(stringResource(R.string.settings_xray_core_release_published, release.publishedAt.take(ISO_DATE_LENGTH)))
        if (release.prerelease) add(stringResource(R.string.settings_xray_core_release_prerelease))
        if (newer) add(stringResource(R.string.settings_xray_core_release_newer))
        if (installed) add(stringResource(R.string.settings_xray_core_release_installed))
    }
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(release.tag, style = MaterialTheme.typography.bodyLarge)
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!installed) {
            val label = stringResource(R.string.settings_xray_core_release_install, normalizeXrayVersion(release.tag))
            TooltipIconButton(tooltip = label, onClick = onInstall, enabled = enabled) {
                Icon(Icons.Filled.Download, contentDescription = label)
            }
        }
    }
}

@Composable
private fun OperationCard(operation: XrayCoreOperation, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (operation) {
                is XrayCoreOperation.Downloading -> {
                    val downloaded = Formatter.formatShortFileSize(context, operation.downloaded)
                    val total = operation.total
                    Text(
                        when {
                            operation.downloaded == 0L -> stringResource(R.string.settings_xray_core_downloading, operation.tag)
                            total == null -> stringResource(R.string.settings_xray_core_downloading_progress, operation.tag, downloaded)
                            else -> stringResource(
                                R.string.settings_xray_core_downloading_progress_with_total,
                                operation.tag,
                                downloaded,
                                Formatter.formatShortFileSize(context, total),
                            )
                        },
                    )
                    if (total == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { (operation.downloaded.toFloat() / total).coerceAtMost(1f) }, modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.settings_xray_core_cancel))
                    }
                }
                XrayCoreOperation.Verifying -> {
                    Text(stringResource(R.string.settings_xray_core_verifying))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is XrayCoreOperation.Installed -> ResultContent(
                    text = stringResource(R.string.settings_xray_core_installed_result, normalizeXrayVersion(operation.core.version)),
                    onDismiss = onDismiss,
                )
                is XrayCoreOperation.Failed -> ResultContent(text = failureText(operation.failure), onDismiss = onDismiss, isError = true)
            }
        }
    }
}

@Composable
private fun ResultContent(text: String, onDismiss: () -> Unit, isError: Boolean = false) {
    Text(text, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_xray_core_dismiss)) }
}

@Composable
private fun failureText(failure: XrayCoreFailure): String = when (failure) {
    XrayCoreFailure.Network -> stringResource(R.string.settings_xray_core_failed_network)
    XrayCoreFailure.RateLimited -> stringResource(R.string.settings_xray_core_failed_rate_limited)
    XrayCoreFailure.Integrity -> stringResource(R.string.settings_xray_core_failed_integrity)
    XrayCoreFailure.Unsupported -> stringResource(R.string.settings_xray_core_failed_unsupported)
    XrayCoreFailure.Broken -> stringResource(R.string.settings_xray_core_failed_broken)
    XrayCoreFailure.TooOld -> stringResource(R.string.settings_xray_core_failed_too_old, MINIMUM_XRAY_VERSION)
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ActionRow(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val SHA256_PREVIEW_CHARS = 12
private const val ISO_DATE_LENGTH = 10
