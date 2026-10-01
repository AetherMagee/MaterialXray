package com.material.xray.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimatedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded
    if (!state.currentState && !state.targetState) return

    val transition = rememberTransition(state, label = "menu")
    val spatialSpec = spring<Float>(dampingRatio = 0.9f, stiffness = 1400f)
    val effectsSpec = spring<Float>(dampingRatio = 1f, stiffness = 3800f)
    val scale by transition.animateFloat(transitionSpec = { spatialSpec }, label = "scale") { if (it) 1f else 0.8f }
    val alpha by transition.animateFloat(transitionSpec = { effectsSpec }, label = "alpha") { if (it) 1f else 0f }
    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    val density = LocalDensity.current
    val margin = with(density) { 48.dp.roundToPx() }
    val shadowPadding = with(density) { 16.dp.roundToPx() }
    val maxHeight = with(density) { (LocalWindowInfo.current.containerSize.height - margin * 2).coerceAtLeast(1).toDp() }
    val positionProvider = remember(margin, shadowPadding) { MenuPositionProvider(margin, shadowPadding) { origin = it } }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                    transformOrigin = origin
                    // Draw the shadow and menu into one texture. Padding contains the blur,
                    // so the alpha layer neither clips it nor leaves an Android elevation shadow behind.
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .padding(16.dp),
        ) {
            Surface(
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .dropShadow(
                        MenuDefaults.shape,
                        Shadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.22f), offset = DpOffset(0.dp, 2.dp)),
                    ),
                shape = MenuDefaults.shape,
                color = MenuDefaults.containerColor,
                tonalElevation = MenuDefaults.TonalElevation,
                shadowElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .width(IntrinsicSize.Max)
                        .verticalScroll(rememberScrollState()),
                    content = content,
                )
            }
        }
    }
}

private class MenuPositionProvider(
    private val margin: Int,
    private val shadowPadding: Int,
    private val onPosition: (TransformOrigin) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val menuWidth = (popupContentSize.width - shadowPadding * 2).coerceAtLeast(1)
        val menuHeight = (popupContentSize.height - shadowPadding * 2).coerceAtLeast(1)
        val start = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - menuWidth
        val end = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - menuWidth else anchorBounds.left
        val menuX = listOf(start, end).firstOrNull { it >= shadowPadding && it + menuWidth <= windowSize.width - shadowPadding }
            ?: start.coerceIn(shadowPadding, (windowSize.width - menuWidth - shadowPadding).coerceAtLeast(shadowPadding))
        val menuY = listOf(anchorBounds.bottom, anchorBounds.top - menuHeight, anchorBounds.top - menuHeight / 2)
            .firstOrNull { it >= margin && it + menuHeight <= windowSize.height - margin }
            ?: anchorBounds.bottom.coerceIn(margin, (windowSize.height - margin - menuHeight).coerceAtLeast(margin))
        val x = menuX - shadowPadding
        val y = menuY - shadowPadding
        onPosition(
            TransformOrigin(
                ((anchorBounds.center.x - x).toFloat() / popupContentSize.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                ((anchorBounds.center.y - y).toFloat() / popupContentSize.height.coerceAtLeast(1)).coerceIn(0f, 1f),
            ),
        )
        return IntOffset(x, y)
    }
}
