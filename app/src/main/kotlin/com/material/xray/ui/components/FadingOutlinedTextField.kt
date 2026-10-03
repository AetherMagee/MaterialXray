package com.material.xray.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * An outlined text field whose text fades out at an edge it can be scrolled past.
 *
 * It only opens the keyboard on focus while the user is touching the screen. With a TV remote or
 * a keyboard, focus passes through it like any other control, and OK opens the keyboard.
 */
@Composable
fun FadingOutlinedTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    inputTransformation: InputTransformation? = null,
    outputTransformation: OutputTransformation? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = OutlinedTextFieldDefaults.colors()
    val color = textStyle.color.takeOrElse {
        when {
            isError -> colors.errorTextColor
            focused -> colors.focusedTextColor
            else -> colors.unfocusedTextColor
        }
    }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val labelLineHeight = MaterialTheme.typography.bodySmall.lineHeight
    val labelPadding = with(density) { (if (labelLineHeight.isSp) labelLineHeight else 16.sp).toDp() / 2 }
    val errorMessage = stringResource(androidx.compose.ui.R.string.default_error_message)
    val singleLine = lineLimits == TextFieldLineLimits.SingleLine
    // Without a touch, focus alone doesn't start an input session, so the field's own OK handling
    // has no keyboard to show. Asking for one here makes the field start the session itself.
    var keyboardRequested by remember { mutableStateOf(false) }
    val touching = LocalInputModeManager.current.inputMode == InputMode.Touch
    LaunchedEffect(focused) { if (!focused) keyboardRequested = false }
    val materialDecorator = OutlinedTextFieldDefaults.decorator(
        state = state,
        enabled = true,
        lineLimits = lineLimits,
        outputTransformation = outputTransformation,
        interactionSource = interactionSource,
        label = if (label == null) {
            null
        } else {
            { label() }
        },
        placeholder = placeholder,
        trailingIcon = trailingIcon,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        colors = colors,
    )

    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            state = state,
            modifier = modifier
                .onPreviewKeyEvent { event ->
                    // Only a remote's OK: Enter must keep reaching the field as a newline or submit.
                    val opensKeyboard = !touching && !keyboardRequested && event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter
                    if (opensKeyboard) keyboardRequested = true
                    opensKeyboard
                }
                .semantics(mergeDescendants = true) { if (isError) error(errorMessage) }
                .then(if (label == null) Modifier else Modifier.padding(top = labelPadding))
                .defaultMinSize(minWidth = OutlinedTextFieldDefaults.MinWidth, minHeight = OutlinedTextFieldDefaults.MinHeight),
            textStyle = textStyle.merge(TextStyle(color = color)),
            inputTransformation = inputTransformation,
            keyboardOptions = keyboardOptions.copy(
                showKeyboardOnFocus = keyboardRequested || touching,
            ),
            lineLimits = lineLimits,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
            outputTransformation = outputTransformation,
            decorator = { innerTextField ->
                materialDecorator.Decoration {
                    Box(modifier = Modifier.textEdgeFade(scroll, horizontal = singleLine)) { innerTextField() }
                }
            },
            scrollState = scroll,
        )
    }
}

/** Shows every character as a dot, for a secret the user has not asked to reveal. */
object MaskOutputTransformation : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        // One replacement per character keeps the cursor mapped to the character it sits on.
        for (index in 0 until length) replace(index, index + 1, "\u2022")
    }
}

@Composable
private fun Modifier.textEdgeFade(scroll: ScrollState, horizontal: Boolean): Modifier {
    val fadeWidth = with(LocalDensity.current) { 16.dp.toPx() }
    val rtl = horizontal && LocalLayoutDirection.current == LayoutDirection.Rtl
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val length = (if (horizontal) size.width else size.height).coerceAtMost(fadeWidth * 2) / 2
            if (length <= 0f || scroll.maxValue == 0 || scroll.maxValue == Int.MAX_VALUE) return@drawWithContent
            val backward = (scroll.value / length).coerceIn(0f, 1f)
            val forward = ((scroll.maxValue - scroll.value) / length).coerceIn(0f, 1f)
            val start = if (rtl) forward else backward
            val end = if (rtl) backward else forward
            val axisLength = if (horizontal) size.width else size.height
            if (start > 0f) {
                val colors = listOf(Color.Black.copy(alpha = 1f - start), Color.Black)
                drawRect(
                    brush = if (horizontal) Brush.horizontalGradient(colors, endX = length) else Brush.verticalGradient(colors, endY = length),
                    size = if (horizontal) Size(length, size.height) else Size(size.width, length),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (end > 0f) {
                val colors = listOf(Color.Black, Color.Black.copy(alpha = 1f - end))
                drawRect(
                    brush = if (horizontal) {
                        Brush.horizontalGradient(colors, startX = axisLength - length, endX = axisLength)
                    } else {
                        Brush.verticalGradient(colors, startY = axisLength - length, endY = axisLength)
                    },
                    topLeft = if (horizontal) Offset(axisLength - length, 0f) else Offset(0f, axisLength - length),
                    size = if (horizontal) Size(length, size.height) else Size(size.width, length),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}
