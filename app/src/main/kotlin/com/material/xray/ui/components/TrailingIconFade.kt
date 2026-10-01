package com.material.xray.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize

@Composable
fun rememberTrailingIconFade(): TrailingIconFade {
    val surface = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    return remember(surface, density) { TrailingIconFade(surface, with(density) { 16.dp.toPx() }, with(density) { 4.dp.toPx() }) }
}

@Stable
class TrailingIconFade(private val surface: Color, private val width: Float, private val padding: Float) {
    private var fieldPosition by mutableStateOf(Offset.Zero)
    private var iconBounds by mutableStateOf(Rect.Zero)

    val iconModifier: Modifier = Modifier.onGloballyPositioned {
        iconBounds = Rect(it.positionInWindow(), it.size.toSize())
    }

    val fieldModifier: Modifier = Modifier
        .onGloballyPositioned { fieldPosition = it.positionInWindow() }
        .drawWithContent {
            drawContent()
            if (iconBounds != Rect.Zero) {
                val end = iconBounds.left - fieldPosition.x - padding
                drawRect(
                    brush = Brush.horizontalGradient(listOf(surface.copy(alpha = 0f), surface), startX = end - width, endX = end),
                    topLeft = Offset(end - width, iconBounds.top - fieldPosition.y),
                    size = Size(width, iconBounds.height),
                )
            }
        }
}
