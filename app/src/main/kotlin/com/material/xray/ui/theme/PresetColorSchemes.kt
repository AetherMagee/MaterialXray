package com.material.xray.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.material.xray.model.ThemePreset
import hct.Hct
import kotlin.math.roundToInt
import scheme.SchemeTonalSpot

internal fun presetColorScheme(
    preset: ThemePreset,
    darkTheme: Boolean,
): ColorScheme {
    require(preset != ThemePreset.Dynamic) { "Dynamic color uses the platform color scheme" }
    val scheme = SchemeTonalSpot(
        Hct.fromInt(preset.seed.toInt()),
        darkTheme,
        0.0,
    )
    return if (darkTheme) {
        darkColorScheme(
            primary = Color(scheme.primary),
            onPrimary = Color(scheme.onPrimary),
            primaryContainer = Color(scheme.primaryContainer),
            onPrimaryContainer = Color(scheme.onPrimaryContainer),
            inversePrimary = Color(scheme.inversePrimary),
            secondary = Color(scheme.secondary),
            onSecondary = Color(scheme.onSecondary),
            secondaryContainer = Color(scheme.secondaryContainer),
            onSecondaryContainer = Color(scheme.onSecondaryContainer),
            tertiary = Color(scheme.tertiary),
            onTertiary = Color(scheme.onTertiary),
            tertiaryContainer = Color(scheme.tertiaryContainer),
            onTertiaryContainer = Color(scheme.onTertiaryContainer),
            background = Color(scheme.background),
            onBackground = Color(scheme.onBackground),
            surface = Color(scheme.surface),
            onSurface = Color(scheme.onSurface),
            surfaceVariant = Color(scheme.surfaceVariant),
            onSurfaceVariant = Color(scheme.onSurfaceVariant),
            surfaceTint = Color(scheme.surfaceTint),
            inverseSurface = Color(scheme.inverseSurface),
            inverseOnSurface = Color(scheme.inverseOnSurface),
            error = Color(scheme.error),
            onError = Color(scheme.onError),
            errorContainer = Color(scheme.errorContainer),
            onErrorContainer = Color(scheme.onErrorContainer),
            outline = Color(scheme.outline),
            outlineVariant = Color(scheme.outlineVariant),
            scrim = Color(scheme.scrim),
            surfaceBright = Color(scheme.surfaceBright),
            surfaceDim = Color(scheme.surfaceDim),
            surfaceContainerLowest = Color(scheme.surfaceContainerLowest),
            surfaceContainerLow = Color(scheme.surfaceContainerLow),
            surfaceContainer = Color(scheme.surfaceContainer),
            surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
            surfaceContainerHighest = Color(scheme.surfaceContainerHighest),
            primaryFixed = Color(scheme.primaryFixed),
            primaryFixedDim = Color(scheme.primaryFixedDim),
            onPrimaryFixed = Color(scheme.onPrimaryFixed),
            onPrimaryFixedVariant = Color(scheme.onPrimaryFixedVariant),
            secondaryFixed = Color(scheme.secondaryFixed),
            secondaryFixedDim = Color(scheme.secondaryFixedDim),
            onSecondaryFixed = Color(scheme.onSecondaryFixed),
            onSecondaryFixedVariant = Color(scheme.onSecondaryFixedVariant),
            tertiaryFixed = Color(scheme.tertiaryFixed),
            tertiaryFixedDim = Color(scheme.tertiaryFixedDim),
            onTertiaryFixed = Color(scheme.onTertiaryFixed),
            onTertiaryFixedVariant = Color(scheme.onTertiaryFixedVariant),
        )
    } else {
        lightColorScheme(
            primary = Color(scheme.primary),
            onPrimary = Color(scheme.onPrimary),
            primaryContainer = Color(scheme.primaryContainer),
            onPrimaryContainer = Color(scheme.onPrimaryContainer),
            inversePrimary = Color(scheme.inversePrimary),
            secondary = Color(scheme.secondary),
            onSecondary = Color(scheme.onSecondary),
            secondaryContainer = Color(scheme.secondaryContainer),
            onSecondaryContainer = Color(scheme.onSecondaryContainer),
            tertiary = Color(scheme.tertiary),
            onTertiary = Color(scheme.onTertiary),
            tertiaryContainer = Color(scheme.tertiaryContainer),
            onTertiaryContainer = Color(scheme.onTertiaryContainer),
            background = Color(scheme.background),
            onBackground = Color(scheme.onBackground),
            surface = Color(scheme.surface),
            onSurface = Color(scheme.onSurface),
            surfaceVariant = Color(scheme.surfaceVariant),
            onSurfaceVariant = Color(scheme.onSurfaceVariant),
            surfaceTint = Color(scheme.surfaceTint),
            inverseSurface = Color(scheme.inverseSurface),
            inverseOnSurface = Color(scheme.inverseOnSurface),
            error = Color(scheme.error),
            onError = Color(scheme.onError),
            errorContainer = Color(scheme.errorContainer),
            onErrorContainer = Color(scheme.onErrorContainer),
            outline = Color(scheme.outline),
            outlineVariant = Color(scheme.outlineVariant),
            scrim = Color(scheme.scrim),
            surfaceBright = Color(scheme.surfaceBright),
            surfaceDim = Color(scheme.surfaceDim),
            surfaceContainerLowest = Color(scheme.surfaceContainerLowest),
            surfaceContainerLow = Color(scheme.surfaceContainerLow),
            surfaceContainer = Color(scheme.surfaceContainer),
            surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
            surfaceContainerHighest = Color(scheme.surfaceContainerHighest),
            primaryFixed = Color(scheme.primaryFixed),
            primaryFixedDim = Color(scheme.primaryFixedDim),
            onPrimaryFixed = Color(scheme.onPrimaryFixed),
            onPrimaryFixedVariant = Color(scheme.onPrimaryFixedVariant),
            secondaryFixed = Color(scheme.secondaryFixed),
            secondaryFixedDim = Color(scheme.secondaryFixedDim),
            onSecondaryFixed = Color(scheme.onSecondaryFixed),
            onSecondaryFixedVariant = Color(scheme.onSecondaryFixedVariant),
            tertiaryFixed = Color(scheme.tertiaryFixed),
            tertiaryFixedDim = Color(scheme.tertiaryFixedDim),
            onTertiaryFixed = Color(scheme.onTertiaryFixed),
            onTertiaryFixedVariant = Color(scheme.onTertiaryFixedVariant),
        )
    }
}

internal fun shouldApplyBlackBackgrounds(
    colorScheme: ColorScheme,
    darkTheme: Boolean,
    oledDark: Boolean,
    detectDynamicPureBlack: Boolean,
): Boolean {
    if (!darkTheme) return false
    if (oledDark) return true
    if (!detectDynamicPureBlack) return false
    val surfaceLuminance = Hct.fromInt(colorScheme.surfaceContainerLowest.toArgb()).tone / 100.0
    return surfaceLuminance < PURE_BLACK_SURFACE_LUMINANCE
}

/**
 * Black background with containers that survive it: a system pure-black scheme can send near-black
 * container tones, which would make cards indistinguishable from the page, so any near-black
 * container is lifted onto a neutral step above the background. Colored containers keep their hue.
 */
internal fun withBlackBackgrounds(colorScheme: ColorScheme): ColorScheme = colorScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = blendBlackOverlay(colorScheme.surfaceDim, PURE_BLACK_SURFACE_LUMINANCE_FLOAT),
    surfaceContainerLowest = blendBlackOverlay(colorScheme.surfaceContainerLowest, 0.0f),
    surfaceContainerLow = blendBlackOverlay(colorScheme.surfaceContainerLow, 0.04f),
    surfaceContainer = blendBlackOverlay(colorScheme.surfaceContainer, 0.07f),
    surfaceContainerHigh = blendBlackOverlay(colorScheme.surfaceContainerHigh, 0.10f),
    surfaceContainerHighest = blendBlackOverlay(colorScheme.surfaceContainerHighest, 0.14f),
)

/** Neutral step above black: near-black inputs become the overlay tone, lighter tones stay. */
private fun blendBlackOverlay(color: Color, overlayLuminance: Float): Color {
    if (Hct.fromInt(color.toArgb()).tone / 100.0 > PURE_BLACK_SURFACE_LUMINANCE) return color
    val channel = (overlayLuminance * 255f).roundToInt()
    return Color(channel, channel, channel)
}

private const val PURE_BLACK_SURFACE_LUMINANCE = 0.03
private const val PURE_BLACK_SURFACE_LUMINANCE_FLOAT = 0.03f
