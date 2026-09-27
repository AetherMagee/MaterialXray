package com.material.xray.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.material.xray.model.ThemePreset
import com.material.xray.ui.configviewer.DarkJsonSyntaxColors
import com.material.xray.ui.configviewer.LightJsonSyntaxColors
import hct.Hct
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetColorSchemesTest {
    @Test
    fun `generated preset schemes land on the documented role tones`() {
        ThemePreset.entries
            .filter { it != ThemePreset.Dynamic }
            .forEach { preset ->
                listOf(false, true).forEach { darkTheme ->
                    val scheme = withAppSurfaces(presetColorScheme(preset, darkTheme), blackPage = false)
                    documentedRoles(scheme, darkTheme).forEach { role ->
                        val actual = tone(role.color)
                        assertTrue(
                            "${preset.value} ${modeOf(darkTheme)} ${role.name} landed on $actual " +
                                "instead of ${role.tone}",
                            abs(actual - role.tone) <= TONE_TOLERANCE,
                        )
                    }
                }
            }
    }

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
    fun `the accents keep the hue of their preset's seed`() {
        ThemePreset.entries
            .filter { it != ThemePreset.Dynamic }
            .forEach { preset ->
                listOf(false, true).forEach { darkTheme ->
                    val scheme = presetColorScheme(preset, darkTheme)
                    val context = "${preset.value} ${modeOf(darkTheme)}"
                    val seedHue = Hct.fromInt(preset.seed.toInt()).hue

                    listOf(
                        "primary" to scheme.primary,
                        "primaryContainer" to scheme.primaryContainer,
                        "secondary" to scheme.secondary,
                        "secondaryContainer" to scheme.secondaryContainer,
                    ).forEach { (name, colour) ->
                        assertTrue(
                            "$context $name drifted to hue ${hueOf(colour)} from the seed's $seedHue",
                            hueDistance(hueOf(colour), seedHue) <= HUE_TOLERANCE,
                        )
                    }

                    assertTrue(
                        "$context tertiary drifted to hue ${hueOf(scheme.tertiary)}",
                        hueDistance(hueOf(scheme.tertiary), (seedHue + 60.0) % 360.0) <= HUE_TOLERANCE,
                    )
                    assertTrue(
                        "$context error drifted to hue ${hueOf(scheme.error)}",
                        hueDistance(hueOf(scheme.error), ERROR_HUE) <= HUE_TOLERANCE,
                    )
                }
            }
    }

    @Test
    fun `the container levels stay distinct and walk away from the page`() {
        ThemePreset.entries
            .filter { it != ThemePreset.Dynamic }
            .forEach { preset ->
                listOf(false, true).forEach { darkTheme ->
                    val scheme = withAppSurfaces(presetColorScheme(preset, darkTheme), blackPage = false)
                    val context = "${preset.value} ${modeOf(darkTheme)}"
                    val levels = listOf(
                        "surface container lowest" to scheme.surfaceContainerLowest,
                        "surface container low" to scheme.surfaceContainerLow,
                        "surface container" to scheme.surfaceContainer,
                        "surface container high" to scheme.surfaceContainerHigh,
                        "surface container highest" to scheme.surfaceContainerHighest,
                    )

                    val steps = levels.zipWithNext().map { (lower, higher) -> tone(lower.second) - tone(higher.second) }
                    steps.forEach { step ->
                        assertTrue("$context stepped by only ${abs(step)} tones", abs(step) >= MIN_LEVEL_STEP)
                    }
                    assertTrue(
                        "$context reversed direction between levels",
                        steps.all { it > 0 } || steps.all { it < 0 },
                    )
                    levels.forEach { (name, level) ->
                        val gap = abs(tone(level) - tone(scheme.surface))
                        assertTrue("$context $name merged into the page at $gap tones", gap >= MIN_LEVEL_STEP)
                    }
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
    fun `pure black lands the page on true black and the containers on their dark tones`() {
        listOf(
            "the compatibility fallback" to DefaultBlueDarkColorScheme,
            "a system scheme that came out near black" to darkColorScheme().copy(surfaceContainerLowest = Color.Black),
        ).forEach { (origin, source) ->
            val black = withAppSurfaces(source, blackPage = true)

            assertEquals("$origin: the page was not true black", 0.0, tone(black.background), TONE_TOLERANCE)
            assertEquals("$origin: the surface was not true black", 0.0, tone(black.surface), TONE_TOLERANCE)
            assertEquals("$origin: the dim surface was not true black", 0.0, tone(black.surfaceDim), TONE_TOLERANCE)

            listOf(
                "surface container lowest" to (black.surfaceContainerLowest to 4.0),
                "surface container low" to (black.surfaceContainerLow to 10.0),
                "surface container" to (black.surfaceContainer to 12.0),
                "surface container high" to (black.surfaceContainerHigh to 17.0),
                "surface container highest" to (black.surfaceContainerHighest to 22.0),
            ).forEach { (name, layer) ->
                val (colour, expected) = layer
                assertTrue(
                    "$origin: $name landed on ${tone(colour)} instead of $expected",
                    abs(tone(colour) - expected) <= TONE_TOLERANCE,
                )
            }

            assertEquals(
                "$origin: the selection fill lost its colour",
                tone(source.primaryContainer),
                tone(black.primaryContainer),
                TONE_TOLERANCE,
            )
        }

        val light = withAppSurfaces(DefaultBlueLightColorScheme, blackPage = false)
        assertEquals(tone(DefaultBlueLightColorScheme.background), tone(light.background), TONE_TOLERANCE)
        assertEquals(
            tone(DefaultBlueLightColorScheme.surfaceContainerLowest),
            tone(light.surfaceContainerLowest),
            TONE_TOLERANCE,
        )
    }

    @Test
    fun `the app draws no tonal elevation over its containers`() {
        themeCases().forEach { case ->
            assertEquals(
                "${case.label} kept a tonal elevation tint",
                Color.Transparent,
                case.scheme.surfaceTint,
            )
        }
    }

    @Test
    fun `content stays readable on every surface`() {
        themeCases().forEach { case ->
            val scheme = case.scheme
            listOf(
                "the page" to scheme.background,
                "wells" to scheme.surfaceContainerLowest,
                "cards and rows" to scheme.surfaceContainerLow,
                "floating elements" to scheme.surfaceContainer,
                "the bottom bar" to scheme.surfaceContainerHigh,
                "chips" to scheme.surfaceContainerHighest,
                "a selection fill" to scheme.primaryContainer,
            ).forEach { (label, backdrop) ->
                assertContrast(case, "onSurface on $label", scheme.onSurface, backdrop, MIN_TEXT_CONTRAST)
                assertContrast(case, "onSurfaceVariant on $label", scheme.onSurfaceVariant, backdrop, MIN_TEXT_CONTRAST)
            }

            listOf(
                Triple("onPrimaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer),
                Triple("onSecondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer),
                Triple("onTertiaryContainer", scheme.onTertiaryContainer, scheme.tertiaryContainer),
                Triple("onErrorContainer", scheme.onErrorContainer, scheme.errorContainer),
            ).forEach { (label, content, container) ->
                assertContrast(case, "$label on its container", content, container, MIN_TEXT_CONTRAST)
            }

            assertContrast(case, "outline on a card", scheme.outline, scheme.surfaceContainerLow, MIN_CONTROL_CONTRAST)
            assertContrast(case, "outline on a well", scheme.outline, scheme.surfaceContainerLowest, MIN_CONTROL_CONTRAST)
            assertContrast(
                case,
                "outlineVariant divider on a card",
                scheme.outlineVariant,
                scheme.surfaceContainerLow,
                MIN_DIVIDER_CONTRAST,
            )
        }
    }

    @Test
    fun `disabled content stays visible on the surfaces it is drawn on`() {
        themeCases().forEach { case ->
            val scheme = case.scheme
            listOf(
                "cards and rows" to scheme.surfaceContainerLow,
                "floating elements" to scheme.surfaceContainer,
                "the bottom bar" to scheme.surfaceContainerHigh,
            ).forEach { (label, backdrop) ->
                listOf(
                    "onSurface" to scheme.onSurface,
                    "onSurfaceVariant" to scheme.onSurfaceVariant,
                ).forEach { (role, content) ->
                    val faded = blendOver(content, backdrop, DISABLED_ALPHA)
                    assertContrast(case, "disabled $role on $label", faded, backdrop, MIN_DISABLED_CONTRAST)
                }
            }
        }
    }

    @Test
    fun `json syntax colours stay readable on the code well`() {
        themeCases().forEach { case ->
            val well = case.scheme.surfaceContainerLowest
            val palette = if (case.darkTheme) DarkJsonSyntaxColors else LightJsonSyntaxColors
            listOf(
                "key" to palette.key,
                "string" to palette.stringValue,
                "number" to palette.number,
                "literal" to palette.literal,
                "punctuation" to palette.punctuation,
                "plain" to palette.plain,
            ).forEach { (kind, colour) ->
                assertContrast(case, "$kind on the code well", colour, well, MIN_TEXT_CONTRAST)
            }
        }
    }

    private fun assertContrast(
        case: ThemeCase,
        label: String,
        content: Color,
        backdrop: Color,
        minimum: Double,
    ) {
        val ratio = contrastRatio(content, backdrop)
        assertTrue("${case.label}: $label reached only $ratio", ratio >= minimum)
    }
}

/** A theme the app can show, each already drawn through the app's surface rules. */
private data class ThemeCase(
    val label: String,
    val scheme: ColorScheme,
    val darkTheme: Boolean,
)

/**
 * Every shape of scheme the app can draw: the palette presets, the fallback, the Material defaults,
 * a monochrome scheme, and the three ways pure black arrives.
 */
private fun themeCases(): List<ThemeCase> = buildList {
    ThemePreset.entries
        .filter { it != ThemePreset.Dynamic }
        .forEach { preset ->
            for (darkTheme in listOf(false, true)) {
                add(
                    ThemeCase(
                        label = "${preset.value} ${modeOf(darkTheme)}",
                        scheme = withAppSurfaces(presetColorScheme(preset, darkTheme), blackPage = false),
                        darkTheme = darkTheme,
                    ),
                )
            }
        }
    for (darkTheme in listOf(false, true)) {
        val fallback = if (darkTheme) DefaultBlueDarkColorScheme else DefaultBlueLightColorScheme
        add(
            ThemeCase(
                label = "fallback ${modeOf(darkTheme)}",
                scheme = withAppSurfaces(fallback, blackPage = false),
                darkTheme = darkTheme,
            ),
        )
        add(
            ThemeCase(
                label = "material defaults ${modeOf(darkTheme)}",
                scheme = withAppSurfaces(
                    if (darkTheme) darkColorScheme() else lightColorScheme(),
                    blackPage = false,
                ),
                darkTheme = darkTheme,
            ),
        )
        add(
            ThemeCase(
                label = "monochrome ${modeOf(darkTheme)}",
                scheme = withAppSurfaces(monochromeScheme(darkTheme), blackPage = false),
                darkTheme = darkTheme,
            ),
        )
    }
    add(
        ThemeCase(
            label = "system pure black dynamic",
            scheme = withAppSurfaces(
                darkColorScheme().copy(surfaceContainerLowest = Color.Black),
                blackPage = true,
            ),
            darkTheme = true,
        ),
    )
    add(
        ThemeCase(
            label = "teal pure black",
            scheme = withAppSurfaces(presetColorScheme(ThemePreset.Teal, darkTheme = true), blackPage = true),
            darkTheme = true,
        ),
    )
    add(
        ThemeCase(
            label = "fallback pure black",
            scheme = withAppSurfaces(DefaultBlueDarkColorScheme, blackPage = true),
            darkTheme = true,
        ),
    )
}

private fun monochromeScheme(darkTheme: Boolean): ColorScheme = if (darkTheme) {
    darkColorScheme(
        primary = Color(0xFF9E9E9E),
        onPrimary = Color(0xFF1B1B1B),
        primaryContainer = Color(0xFF3A3A3A),
        onPrimaryContainer = Color(0xFFE0E0E0),
        surface = Color(0xFF121212),
    )
} else {
    lightColorScheme(
        primary = Color(0xFF5E5E5E),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE0E0E0),
        onPrimaryContainer = Color(0xFF1B1B1B),
        surface = Color(0xFFFCFCFC),
    )
}

private fun modeOf(darkTheme: Boolean): String = if (darkTheme) "dark" else "light"

/** One role the app draws with and the tone Material documents for it. */
private data class RoleTone(val name: String, val color: Color, val tone: Double)

/**
 * The documented tones of the roles the app draws with, written out here on purpose rather than
 * read back from the implementation, so the table cannot follow the code it checks.
 */
private fun documentedRoles(scheme: ColorScheme, darkTheme: Boolean): List<RoleTone> {
    fun role(name: String, color: Color, light: Double, dark: Double) = RoleTone(name, color, if (darkTheme) dark else light)

    return listOf(
        role("background", scheme.background, 98.0, 6.0),
        role("surface", scheme.surface, 98.0, 6.0),
        role("surfaceDim", scheme.surfaceDim, 87.0, 6.0),
        role("surfaceBright", scheme.surfaceBright, 98.0, 24.0),
        role("surfaceContainerLowest", scheme.surfaceContainerLowest, 100.0, 4.0),
        role("surfaceContainerLow", scheme.surfaceContainerLow, 96.0, 10.0),
        role("surfaceContainer", scheme.surfaceContainer, 94.0, 12.0),
        role("surfaceContainerHigh", scheme.surfaceContainerHigh, 92.0, 17.0),
        role("surfaceContainerHighest", scheme.surfaceContainerHighest, 90.0, 22.0),
        role("surfaceVariant", scheme.surfaceVariant, 90.0, 30.0),
        role("primary", scheme.primary, 40.0, 80.0),
        role("onPrimary", scheme.onPrimary, 100.0, 20.0),
        role("primaryContainer", scheme.primaryContainer, 90.0, 30.0),
        role("onPrimaryContainer", scheme.onPrimaryContainer, 10.0, 90.0),
        role("secondary", scheme.secondary, 40.0, 80.0),
        role("onSecondary", scheme.onSecondary, 100.0, 20.0),
        role("secondaryContainer", scheme.secondaryContainer, 90.0, 30.0),
        role("onSecondaryContainer", scheme.onSecondaryContainer, 10.0, 90.0),
        role("tertiary", scheme.tertiary, 40.0, 80.0),
        role("onTertiary", scheme.onTertiary, 100.0, 20.0),
        role("tertiaryContainer", scheme.tertiaryContainer, 90.0, 30.0),
        role("onTertiaryContainer", scheme.onTertiaryContainer, 10.0, 90.0),
        role("error", scheme.error, 40.0, 80.0),
        role("onError", scheme.onError, 100.0, 20.0),
        role("errorContainer", scheme.errorContainer, 90.0, 30.0),
        role("onErrorContainer", scheme.onErrorContainer, 10.0, 90.0),
        role("onSurface", scheme.onSurface, 10.0, 90.0),
        role("onSurfaceVariant", scheme.onSurfaceVariant, 30.0, 80.0),
        role("outline", scheme.outline, 50.0, 60.0),
        role("outlineVariant", scheme.outlineVariant, 80.0, 30.0),
    )
}

private fun tone(color: Color): Double = Hct.fromInt(color.toArgb()).tone

private fun hueOf(color: Color): Double = Hct.fromInt(color.toArgb()).hue

private fun hueDistance(first: Double, second: Double): Double {
    val distance = abs(first - second) % 360.0
    return min(distance, 360.0 - distance)
}

/** Compose fades disabled content by drawing it partly transparent over its own backdrop. */
private fun blendOver(content: Color, backdrop: Color, alpha: Float): Color = Color(
    red = content.red * alpha + backdrop.red * (1 - alpha),
    green = content.green * alpha + backdrop.green * (1 - alpha),
    blue = content.blue * alpha + backdrop.blue * (1 - alpha),
)

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

/**
 * The closest two layers of the app may sit: Material's own levels are two tones apart, and a round
 * trip through 8-bit sRGB can eat into that.
 */
private const val MIN_LEVEL_STEP = 1.0

private const val MIN_TEXT_CONTRAST = 4.5
private const val MIN_CONTROL_CONTRAST = 3.0
private const val MIN_DIVIDER_CONTRAST = 1.3

/** Disabled content is exempt from text contrast, but it must not disappear either. */
private const val DISABLED_ALPHA = 0.38f
private const val MIN_DISABLED_CONTRAST = 1.5

private const val TONE_TOLERANCE = 0.5

/** Every scheme draws its errors from the same red palette, whatever its seed hue is. */
private const val ERROR_HUE = 25.0

/** Gamut mapping moves a hue by a degree or two; a wrong hue is off by far more than this. */
private const val HUE_TOLERANCE = 5.0
