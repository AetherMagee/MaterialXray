package com.material.xray.core.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/**
 * [DetailSheetSceneStrategy] for the current window width.
 *
 * @param minWindowWidth the width from which details open as a sheet (`TwoPaneMinWidth`).
 * @param scrimClickLabel the accessibility label for closing the sheet by tapping the scrim.
 */
@Composable
fun rememberDetailSheetSceneStrategy(minWindowWidth: Dp, scrimClickLabel: String?): DetailSheetSceneStrategy {
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return remember(windowWidth, minWindowWidth, scrimClickLabel) {
        DetailSheetSceneStrategy(windowWidth, minWindowWidth, scrimClickLabel)
    }
}

/**
 * On a window at least [minWindowWidth] wide, shows a [DetailKey] entry as a modal sheet on the end
 * edge over the entry below it, rather than replacing it. On a narrower window it declines, so
 * `NavDisplay`'s single-pane fallback shows the detail full-window.
 */
class DetailSheetSceneStrategy(
    private val windowWidth: Dp,
    private val minWindowWidth: Dp,
    private val scrimClickLabel: String?,
) : SceneStrategy<NavKey> {
    override fun SceneStrategyScope<NavKey>.calculateScene(entries: List<NavEntry<NavKey>>): Scene<NavKey>? {
        if (windowWidth < minWindowWidth || entries.size < 2) return null
        val detailEntry = entries.last()
        if (detailEntry.navKey !is DetailKey) return null
        return DetailSheetScene(
            key = detailEntry.contentKey,
            // Back pops only the detail; the entry underneath is already on screen.
            previousEntries = entries.dropLast(1),
            backgroundEntry = entries[entries.lastIndex - 1],
            detailEntry = detailEntry,
            onDismiss = onBack,
            scrimClickLabel = scrimClickLabel,
        )
    }
}

/**
 * The entry a detail was opened from, with the detail in a sheet along the end edge over a scrim.
 * Tapping the scrim closes the sheet like back does. The scrim and sheet animate in and out with
 * the detail's own transition; the background stays still.
 */
data class DetailSheetScene(
    override val key: Any,
    override val previousEntries: List<NavEntry<NavKey>>,
    val backgroundEntry: NavEntry<NavKey>,
    val detailEntry: NavEntry<NavKey>,
    private val onDismiss: () -> Unit,
    private val scrimClickLabel: String?,
) : Scene<NavKey> {
    override val entries: List<NavEntry<NavKey>> = listOf(backgroundEntry, detailEntry)

    override val content: @Composable () -> Unit = {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val sheetWidth = (maxWidth * DETAIL_SHEET_WIDTH_FRACTION).coerceIn(DetailSheetMinWidth, DetailSheetMaxWidth)
            backgroundEntry.Content()
            val detailKey = detailEntry.navKey as DetailKey
            val animatedScope = LocalNavAnimatedContentScope.current
            Box(
                modifier = with(animatedScope) {
                    Modifier
                        .fillMaxSize()
                        .animateEnterExit(enter = detailEnterTransition(detailKey), exit = detailExitTransition(detailKey))
                },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = DETAIL_SHEET_SCRIM_ALPHA))
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClickLabel = scrimClickLabel,
                            onClick = onDismiss,
                        ),
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(sheetWidth)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                    shadowElevation = 6.dp,
                ) {
                    detailEntry.Content()
                }
            }
        }
    }
}

private const val DETAIL_SHEET_WIDTH_FRACTION = 0.5f
private val DetailSheetMinWidth = 480.dp
private val DetailSheetMaxWidth = 640.dp
private const val DETAIL_SHEET_SCRIM_ALPHA = 0.32f
