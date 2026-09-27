package com.material.xray.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.material.xray.model.ThemePreset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetColorSchemesTest {
    @Test
    fun `generated preset schemes preserve key contrast pairs`() {
        ThemePreset.entries
            .filter { it != ThemePreset.Dynamic }
            .forEach { preset ->
                listOf(false, true).forEach { darkTheme ->
                    val scheme = presetColorScheme(preset, darkTheme)
                    val mode = if (darkTheme) "dark" else "light"
                    val context = "${preset.value} $mode"

                    assertTrue(
                        "$context onSurface/surface contrast was ${contrastRatio(scheme.onSurface, scheme.surface)}",
                        contrastRatio(scheme.onSurface, scheme.surface) >= 4.5,
                    )
                    assertTrue(
                        "$context onSurfaceVariant/surfaceVariant contrast was " +
                            contrastRatio(scheme.onSurfaceVariant, scheme.surfaceVariant),
                        contrastRatio(scheme.onSurfaceVariant, scheme.surfaceVariant) >= 4.5,
                    )
                    assertTrue(
                        "$context onPrimary/primary contrast was ${contrastRatio(scheme.onPrimary, scheme.primary)}",
                        contrastRatio(scheme.onPrimary, scheme.primary) >= 3.0,
                    )
                }
            }
    }

    @Test
    fun `pure black detection is limited to eligible dark schemes`() {
        val standardDarkScheme = darkColorScheme()
        val pureBlackSystemScheme = standardDarkScheme.copy(surfaceContainerLowest = Color.Black)

        assertTrue(
            shouldApplyBlackBackgrounds(
                colorScheme = pureBlackSystemScheme,
                darkTheme = true,
                oledDark = false,
                detectDynamicPureBlack = true,
            ),
        )
        assertFalse(
            shouldApplyBlackBackgrounds(
                colorScheme = standardDarkScheme,
                darkTheme = true,
                oledDark = false,
                detectDynamicPureBlack = true,
            ),
        )
        assertFalse(
            shouldApplyBlackBackgrounds(
                colorScheme = pureBlackSystemScheme,
                darkTheme = false,
                oledDark = true,
                detectDynamicPureBlack = true,
            ),
        )
        assertFalse(
            shouldApplyBlackBackgrounds(
                colorScheme = pureBlackSystemScheme,
                darkTheme = true,
                oledDark = false,
                detectDynamicPureBlack = false,
            ),
        )
        assertTrue(
            shouldApplyBlackBackgrounds(
                colorScheme = standardDarkScheme,
                darkTheme = true,
                oledDark = true,
                detectDynamicPureBlack = false,
            ),
        )
    }

    @Test
    fun `black backgrounds lift near-black containers and keep colored ones`() {
        val coloredContainer = Color(0xFF1E2A44)
        val scheme = darkColorScheme().copy(
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color.Black,
            surfaceContainer = Color.Black,
            surfaceContainerHigh = Color.Black,
            surfaceContainerHighest = Color.Black,
            primaryContainer = coloredContainer,
        )

        val adjusted = withBlackBackgrounds(scheme)

        assertEquals(Color.Black, adjusted.background)
        assertEquals(Color.Black, adjusted.surface)
        assertEquals(Color.Black, adjusted.surfaceContainerLowest)
        // Near-black containers climb a neutral ladder so cards stay visible on the black page.
        assertTrue(adjusted.surfaceContainerLow != Color.Black)
        assertTrue(adjusted.surfaceContainer != Color.Black)
        assertTrue(adjusted.surfaceContainerHigh != Color.Black)
        assertTrue(adjusted.surfaceContainerHighest != Color.Black)
        assertTrue(
            relativeLuminance(adjusted.surfaceContainerLow) < relativeLuminance(adjusted.surfaceContainer),
        )
        assertTrue(
            relativeLuminance(adjusted.surfaceContainer) < relativeLuminance(adjusted.surfaceContainerHigh),
        )
        assertTrue(
            relativeLuminance(adjusted.surfaceContainerHigh) < relativeLuminance(adjusted.surfaceContainerHighest),
        )
        // Colored roles keep their hue instead of being flattened to the neutral ladder.
        assertEquals(coloredContainer, adjusted.primaryContainer)
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val foregroundLuminance = relativeLuminance(foreground)
        val backgroundLuminance = relativeLuminance(background)
        return (max(foregroundLuminance, backgroundLuminance) + 0.05) /
            (min(foregroundLuminance, backgroundLuminance) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun linearize(channel: Float): Double = if (channel <= 0.04045f) {
            (channel / 12.92f).toDouble()
        } else {
            ((channel + 0.055f) / 1.055f).toDouble().pow(2.4)
        }

        return 0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)
    }
}
