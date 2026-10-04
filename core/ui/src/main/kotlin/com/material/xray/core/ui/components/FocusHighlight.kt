package com.material.xray.core.ui.components

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.material.xray.core.ui.theme.FOCUSED_STATE_LAYER_ALPHA
import kotlinx.coroutines.launch

/**
 * Indication for rows that deliberately skip the tap ripple: it draws only keyboard and remote
 * focus, which touch never gives these rows, so phones see no change.
 */
val FocusHighlight: IndicationNodeFactory = FocusHighlightFactory(RectangleShape, horizontalOutset = 0.dp)

/**
 * [FocusHighlight] for options in a dialog's padded content. A full-width rectangle would hug the
 * radio mark, so this one is rounded and reaches into the padding instead.
 */
val OptionFocusHighlight: IndicationNodeFactory =
    FocusHighlightFactory(RoundedCornerShape(12.dp), horizontalOutset = 12.dp)

private class FocusHighlightFactory(private val shape: Shape, private val horizontalOutset: Dp) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = FocusHighlightNode(interactionSource, shape, horizontalOutset)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = System.identityHashCode(this)
}

private class FocusHighlightNode(
    private val interactionSource: InteractionSource,
    private val shape: Shape,
    private val horizontalOutset: Dp,
) : Modifier.Node(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                    else -> return@collect
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        // Scrolling lists clip only along their scroll axis, so drawing past the sides is safe.
        val outset = horizontalOutset.toPx()
        val outline = shape.createOutline(Size(size.width + 2 * outset, size.height), layoutDirection, this)
        translate(left = -outset) {
            drawOutline(outline, currentValueOf(LocalContentColor).copy(alpha = FOCUSED_STATE_LAYER_ALPHA))
        }
    }
}
