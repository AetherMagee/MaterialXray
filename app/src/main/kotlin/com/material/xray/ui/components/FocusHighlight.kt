package com.material.xray.ui.components

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import com.material.xray.ui.theme.FOCUSED_STATE_LAYER_ALPHA
import kotlinx.coroutines.launch

/**
 * Indication for rows that deliberately skip the tap ripple: it draws only keyboard and remote
 * focus, which touch never gives these rows, so phones see no change.
 */
object FocusHighlight : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = FocusHighlightNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = System.identityHashCode(this)
}

private class FocusHighlightNode(private val interactionSource: InteractionSource) :
    Modifier.Node(),
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
        if (focused) drawRect(currentValueOf(LocalContentColor).copy(alpha = FOCUSED_STATE_LAYER_ALPHA))
    }
}
