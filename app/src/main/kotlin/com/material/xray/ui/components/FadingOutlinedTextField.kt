package com.material.xray.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Keeps the String API's cursor and IME composition, just like BasicTextField's adapter. */
@Composable
fun FadingOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    var editorValue by remember { mutableStateOf(TextFieldValue(value)) }
    val current = editorValue.copy(text = value)
    var lastText by remember(value) { mutableStateOf(value) }
    SideEffect {
        if (current.selection != editorValue.selection || current.composition != editorValue.composition) {
            editorValue = current
        }
    }
    FadingOutlinedTextField(
        value = current,
        onValueChange = {
            editorValue = it
            if (lastText != it.text) {
                lastText = it.text
                onValueChange(it.text)
            }
        },
        modifier = modifier,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        trailingIcon = trailingIcon,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        minLines = minLines,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FadingOutlinedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    minLines: Int = 1,
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
    val requester = remember { BringIntoViewRequester() }
    var previousSelection by remember { mutableStateOf(value.selection) }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val labelLineHeight = MaterialTheme.typography.bodySmall.lineHeight
    val labelPadding = with(density) { (if (labelLineHeight.isSp) labelLineHeight else 16.sp).toDp() / 2 }
    val errorMessage = stringResource(androidx.compose.ui.R.string.default_error_message)

    LaunchedEffect(value.selection, value.text, visualTransformation, textLayout, focused) {
        val cursorOffset = when {
            value.selection.start != previousSelection.start -> value.selection.start
            value.selection.end != previousSelection.end -> value.selection.end
            else -> value.selection.min
        }
        previousSelection = value.selection
        if (focused) {
            textLayout?.let { layout ->
                val transformed = visualTransformation.filter(value.annotatedString)
                val offset = transformed.offsetMapping.originalToTransformed(cursorOffset)
                requester.bringIntoView(layout.getCursorRect(offset.coerceIn(0, layout.layoutInput.text.length)))
            }
        }
    }

    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .semantics(mergeDescendants = true) { if (isError) error(errorMessage) }
                .then(if (label == null) Modifier else Modifier.padding(top = labelPadding))
                .defaultMinSize(minWidth = OutlinedTextFieldDefaults.MinWidth, minHeight = OutlinedTextFieldDefaults.MinHeight),
            textStyle = textStyle.merge(TextStyle(color = color)),
            keyboardOptions = keyboardOptions,
            singleLine = singleLine,
            minLines = minLines,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
            onTextLayout = { textLayout = it },
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value.text,
                    enabled = true,
                    singleLine = singleLine,
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                    label = label,
                    placeholder = placeholder,
                    trailingIcon = trailingIcon,
                    suffix = suffix,
                    supportingText = supportingText,
                    isError = isError,
                    colors = colors,
                    innerTextField = {
                        // Multiline form fields can grow inside a scrolling page. Only a bounded
                        // viewport, such as the JSON editor, needs its own vertical scrolling.
                        BoxWithConstraints(propagateMinConstraints = true) {
                            val scrollable = singleLine || constraints.hasBoundedHeight
                            val scrollModifier = when {
                                singleLine -> Modifier.horizontalScroll(scroll)
                                scrollable -> Modifier.verticalScroll(scroll)
                                else -> Modifier
                            }
                            Box(modifier = Modifier.textEdgeFade(scroll, singleLine, scrollable).then(scrollModifier)) {
                                Box(modifier = Modifier.bringIntoViewRequester(requester)) { innerTextField() }
                            }
                        }
                    },
                )
            },
        )
    }
}

@Composable
private fun Modifier.textEdgeFade(scroll: ScrollState, horizontal: Boolean, scrollable: Boolean): Modifier {
    val fadeWidth = with(LocalDensity.current) { 16.dp.toPx() }
    val rtl = horizontal && LocalLayoutDirection.current == LayoutDirection.Rtl
    if (!scrollable) return this
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
