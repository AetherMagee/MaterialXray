package com.material.xray.ui.logs

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.R
import com.material.xray.service.LogEntry
import com.material.xray.service.LogSource
import com.material.xray.service.displayMessage
import com.material.xray.ui.components.AnimatedDropdownMenu
import com.material.xray.ui.components.AppBarTitle
import com.material.xray.ui.components.AppTopBarHeight
import com.material.xray.ui.components.ScrollFadeEdges
import com.material.xray.ui.components.SegmentedTabRow
import com.material.xray.ui.components.TooltipIconButton
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

private enum class LogFilter(@param:StringRes val labelRes: Int) {
    ALL(R.string.logs_filter_all),
    APP(R.string.logs_filter_app),
    XRAY(R.string.logs_filter_xray),
}

internal enum class LogSeverity { ERROR, WARNING, NORMAL }

internal fun LogEntry.severity(): LogSeverity = when (source) {
    LogSource.XRAY -> when {
        displayMessage.startsWith("[Error]") -> LogSeverity.ERROR
        displayMessage.startsWith("[Warning]") -> LogSeverity.WARNING
        else -> LogSeverity.NORMAL
    }
    LogSource.APP -> if (message.startsWith("ERROR:", ignoreCase = true) || message.contains("fail", ignoreCase = true)) {
        LogSeverity.ERROR
    } else {
        LogSeverity.NORMAL
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(showTitleBarLogo: Boolean, viewModel: LogsViewModel = koinViewModel()) {
    val allEntries by viewModel.entries.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { LogFilter.entries.size })
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var isExporting by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val selectedEntries = remember(allEntries, selectedIds) { allEntries.filter { it.id in selectedIds } }
    val selectionMode = selectedIds.isNotEmpty()
    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }
    LaunchedEffect(allEntries) {
        if (selectedIds.isNotEmpty()) {
            selectedIds = selectedIds.intersect(allEntries.map { it.id }.toSet())
        }
    }
    val saveLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { destination ->
        if (destination != null) {
            coroutineScope.launch {
                isExporting = true
                try {
                    viewModel.saveLogs(destination)
                    Toast.makeText(context, R.string.logs_saved, Toast.LENGTH_SHORT).show()
                } catch (_: IOException) {
                    Toast.makeText(context, R.string.logs_save_failed, Toast.LENGTH_SHORT).show()
                } catch (_: SecurityException) {
                    Toast.makeText(context, R.string.logs_save_failed, Toast.LENGTH_SHORT).show()
                } finally {
                    isExporting = false
                }
            }
        }
    }
    val selectedFilter by remember {
        derivedStateOf { LogFilter.entries[pagerState.targetPage] }
    }

    LaunchedEffect(selectedFilter) { selectedIds = emptySet() }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = {
                    AppBarTitle(
                        if (selectionMode) {
                            stringResource(R.string.logs_selected_count, selectedEntries.size)
                        } else {
                            stringResource(R.string.navigation_logs)
                        },
                        showTitleBarLogo && !selectionMode,
                    )
                },
                navigationIcon = {
                    if (selectionMode) {
                        TooltipIconButton(
                            tooltip = stringResource(R.string.logs_cancel_selection),
                            onClick = { selectedIds = emptySet() },
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.logs_cancel_selection))
                        }
                    }
                },
                expandedHeight = AppTopBarHeight,
                windowInsets = TopAppBarDefaults.windowInsets,
                actions = {
                    if (selectionMode) {
                        TooltipIconButton(
                            tooltip = stringResource(R.string.logs_copy_selected),
                            onClick = {
                                viewModel.copyEntries(selectedEntries)
                                Toast.makeText(context, R.string.logs_copied, Toast.LENGTH_SHORT).show()
                                selectedIds = emptySet()
                            },
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.logs_copy_selected))
                        }
                    } else {
                        Box {
                            TooltipIconButton(
                                tooltip = stringResource(R.string.logs_export),
                                enabled = !isExporting,
                                onClick = { showExportMenu = true },
                            ) {
                                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.logs_export))
                            }
                            AnimatedDropdownMenu(
                                expanded = showExportMenu,
                                onDismissRequest = { showExportMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.logs_save_to_file)) },
                                    leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                                    onClick = {
                                        showExportMenu = false
                                        try {
                                            saveLogsLauncher.launch(LOG_EXPORT_FILE_NAME)
                                        } catch (_: ActivityNotFoundException) {
                                            Toast.makeText(
                                                context,
                                                R.string.logs_save_failed,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.logs_share)) },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                    onClick = {
                                        showExportMenu = false
                                        coroutineScope.launch {
                                            isExporting = true
                                            try {
                                                shareLogFile(context, viewModel.createShareFile())
                                            } catch (_: IOException) {
                                                Toast.makeText(
                                                    context,
                                                    R.string.logs_share_failed,
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            } catch (_: IllegalArgumentException) {
                                                Toast.makeText(
                                                    context,
                                                    R.string.logs_share_failed,
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            } catch (_: ActivityNotFoundException) {
                                                Toast.makeText(
                                                    context,
                                                    R.string.logs_share_failed,
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            } finally {
                                                isExporting = false
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        TooltipIconButton(tooltip = stringResource(R.string.logs_copy_all), onClick = {
                            viewModel.copyAll()
                            Toast.makeText(context, R.string.logs_copied, Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.logs_copy_all))
                        }
                        TooltipIconButton(tooltip = stringResource(R.string.logs_clear), onClick = { viewModel.clear() }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.logs_clear))
                        }
                    }
                },
            )
        },
        bottomBar = {
            SegmentedTabRow(
                labels = LogFilter.entries.map { stringResource(it.labelRes) },
                selectedIndex = LogFilter.entries.indexOf(selectedFilter),
                onSelected = { index ->
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(index)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val pageFilter = LogFilter.entries[page]
                val entries = remember(allEntries, pageFilter) {
                    allEntries.filterBy(pageFilter)
                }
                LogEntriesList(
                    entries = entries,
                    selectedIds = selectedIds,
                    onSelectionChange = { selectedIds = it },
                    onSelect = { entry ->
                        selectedIds = if (entry.id in selectedIds) selectedIds - entry.id else selectedIds + entry.id
                    },
                )
            }
            ScrollFadeEdges()
        }
    }
}

private fun shareLogFile(context: Context, uri: Uri) {
    val label = context.getString(
        R.string.clipboard_label_logs,
        context.getString(R.string.app_name),
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, label)
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(label, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.logs_share)))
}

@Composable
private fun LogEntriesList(
    entries: List<LogEntry>,
    selectedIds: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onSelect: (LogEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val currentEntries by rememberUpdatedState(entries)
    val currentSelectedIds by rememberUpdatedState(selectedIds)
    val changeSelection by rememberUpdatedState(onSelectionChange)
    val hapticFeedback = LocalHapticFeedback.current
    val edgeSize = with(LocalDensity.current) { 48.dp.toPx() }
    var gesture by remember { mutableStateOf<LogDragSelection?>(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }

    fun extendSelection(y: Float) {
        val endId = listState.logAt(y) ?: return
        gesture?.let { changeSelection(it.selectionAt(currentEntries.map { entry -> entry.id }, endId)) }
    }

    LaunchedEffect(gesture) {
        if (gesture == null) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (isActive) {
            val frame = withFrameNanos { it }
            val viewport = listState.layoutInfo
            val edgeDistance = when {
                fingerY < viewport.viewportStartOffset + edgeSize -> fingerY - viewport.viewportStartOffset - edgeSize
                fingerY > viewport.viewportEndOffset - edgeSize -> fingerY - viewport.viewportEndOffset + edgeSize
                else -> 0f
            }.coerceIn(-edgeSize, edgeSize)
            if (edgeDistance != 0f) {
                listState.scrollBy(edgeDistance * 10f * ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(0.05f))
                extendSelection(fingerY)
            }
            previousFrame = frame
        }
    }

    var followTail by listState.rememberFollowTail()

    val selectionMode = selectedIds.isNotEmpty()
    val autoScroll = followTail && !selectionMode && gesture == null
    LaunchedEffect(entries.lastOrNull()?.id, autoScroll) {
        if (autoScroll && entries.isNotEmpty()) {
            listState.scrollToItem(entries.size - 1)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(listState) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val anchorId = listState.logAt(down.position.y) ?: return@awaitEachGesture
                        val longPress = awaitLongPressOrCancellation(down.id)
                        if (longPress == null) {
                            if (currentEvent.changes.any { it.id == down.id && it.changedToUp() } && currentSelectedIds.isNotEmpty()) {
                                changeSelection(if (anchorId in currentSelectedIds) currentSelectedIds - anchorId else currentSelectedIds + anchorId)
                            }
                        } else {
                            try {
                                gesture = LogDragSelection(anchorId, currentSelectedIds)
                                fingerY = longPress.position.y
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                extendSelection(fingerY)
                                longPress.consume()
                                drag(longPress.id) { change ->
                                    fingerY = change.position.y
                                    extendSelection(fingerY)
                                    change.consume()
                                }
                                currentEvent.changes.forEach { it.consume() }
                            } finally {
                                gesture = null
                            }
                        }
                    }
                },
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            itemsIndexed(
                items = entries,
                key = { _, entry -> entry.id },
                contentType = { _, entry -> entry.source },
            ) { index, entry ->
                LogEntryRow(
                    entry = entry,
                    showDivider = index < entries.lastIndex,
                    selected = entry.id in selectedIds,
                    onClick = { if (selectionMode) onSelect(entry) },
                    onLongClick = { onSelect(entry) },
                )
            }
        }
        ScrollToBottomButton(
            visible = !followTail && entries.isNotEmpty(),
            onClick = {
                coroutineScope.launch {
                    listState.animateScrollToItem(currentEntries.lastIndex)
                    followTail = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }
}

@Composable
private fun ScrollToBottomButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier,
    ) {
        SmallFloatingActionButton(onClick = onClick) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.logs_scroll_to_bottom))
        }
    }
}

/**
 * Whether the list should follow new entries: true while the user is at the bottom. Scrolling up
 * clears it, and reaching the bottom again, by hand or with the button, sets it.
 */
@Composable
private fun LazyListState.rememberFollowTail(): MutableState<Boolean> {
    val followTail = remember { mutableStateOf(true) }
    LaunchedEffect(this) {
        snapshotFlow { canScrollForward to lastScrolledBackward }
            .collect { (canScrollForward, scrolledBackward) ->
                if (!canScrollForward) {
                    followTail.value = true
                } else if (scrolledBackward) {
                    followTail.value = false
                }
            }
    }
    return followTail
}

private fun LazyListState.logAt(y: Float): Long? {
    val visible = layoutInfo.visibleItemsInfo
    return (
        visible.firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?: if (y < (visible.firstOrNull()?.offset ?: 0)) visible.firstOrNull() else visible.lastOrNull()
        )?.key as? Long
}

private fun List<LogEntry>.filterBy(filter: LogFilter): List<LogEntry> = when (filter) {
    LogFilter.ALL -> this
    LogFilter.APP -> filter { it.source == LogSource.APP }
    LogFilter.XRAY -> filter { it.source == LogSource.XRAY }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogEntryRow(
    entry: LogEntry,
    showDivider: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val time = remember(entry.timestamp) { timeFormat.format(Date(entry.timestamp)) }
    val warningColor = if (isSystemInDarkTheme()) Color(0xFFFFD54F) else Color(0xFF9A6700)
    val messageColor = when (entry.severity()) {
        LogSeverity.ERROR -> MaterialTheme.colorScheme.error
        LogSeverity.WARNING -> warningColor
        LogSeverity.NORMAL -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                onClick {
                    onClick()
                    true
                }
                onLongClick {
                    onLongClick()
                    true
                }
            },
    ) {
        Text(
            text = "$time [${entry.source.name}] ${entry.displayMessage}",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            color = messageColor,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 3.dp, end = 8.dp, bottom = 4.dp),
        )
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp),
            )
        }
    }
}
