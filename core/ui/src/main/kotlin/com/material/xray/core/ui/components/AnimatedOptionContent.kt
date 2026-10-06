package com.material.xray.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize

private const val OPTION_SIZE_MS = 250
private const val OPTION_FADE_MS = 100
private val OptionEnterEasing = Easing { fraction -> 1f - FastOutSlowInEasing.transform(1f - fraction) }

/** Dependent controls keep their content until collapse finishes; expansion reverses that motion. */
@Composable
fun AnimatedOptionContent(
    visible: Boolean,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontal: Boolean = false,
    blockOutgoingInput: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var containsFocus by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (!visible && containsFocus) focusManager.clearFocus()
    }
    val enterSize = tween<IntSize>(OPTION_SIZE_MS, easing = OptionEnterEasing)
    val exitSize = tween<IntSize>(OPTION_SIZE_MS)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier
            .focusProperties { onEnter = { if (!visible) cancelFocusChange() } }
            .onFocusChanged { containsFocus = it.hasFocus }
            .focusGroup()
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
            .pointerInput(visible, blockOutgoingInput) {
                // Outgoing controls are still drawn, but must not accept actions for a mode
                // that has already been disabled.
                if (!visible && blockOutgoingInput) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
        enter = (if (horizontal) expandHorizontally(enterSize, expandFrom = Alignment.Start) else expandVertically(enterSize, expandFrom = Alignment.Top)) +
            fadeIn(tween(OPTION_FADE_MS, delayMillis = OPTION_SIZE_MS - OPTION_FADE_MS, easing = OptionEnterEasing)),
        exit = (if (horizontal) shrinkHorizontally(exitSize, shrinkTowards = Alignment.Start) else shrinkVertically(exitSize, shrinkTowards = Alignment.Top)) +
            fadeOut(tween(OPTION_FADE_MS)),
        label = "dependentOptions",
    ) {
        Column(verticalArrangement = verticalArrangement, content = content)
    }
}

/** Retain the value shown by an outgoing field, rather than replacing it with the next mode. */
@Composable
fun <T> rememberOptionValue(visible: Boolean, value: T): T {
    val retained = remember { OptionValue(value) }
    if (visible) retained.value = value
    return retained.value
}

private class OptionValue<T>(var value: T)
