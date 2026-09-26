package com.material.xray.model

import androidx.annotation.StringRes
import com.material.xray.R

/**
 * A user-selectable Material 3 color scheme, with dynamic color represented explicitly. Presets are
 * named after their accent seed, so the label matches what the generator produces from it.
 */
enum class ThemePreset(
    val value: String,
    @param:StringRes val labelResource: Int,
    val seed: Long,
) {
    Dynamic(
        value = "dynamic",
        labelResource = R.string.settings_theme_dynamic,
        seed = 0L,
    ),
    Red(
        value = "red",
        labelResource = R.string.settings_theme_red,
        seed = 0xFFF44336,
    ),
    Pink(
        value = "pink",
        labelResource = R.string.settings_theme_pink,
        seed = 0xFFE91E63,
    ),
    Purple(
        value = "purple",
        labelResource = R.string.settings_theme_purple,
        seed = 0xFF9C27B0,
    ),
    Indigo(
        value = "indigo",
        labelResource = R.string.settings_theme_indigo,
        seed = 0xFF3F51B5,
    ),
    Blue(
        value = "blue",
        labelResource = R.string.settings_theme_blue,
        seed = 0xFF2196F3,
    ),
    Teal(
        value = "teal",
        labelResource = R.string.settings_theme_teal,
        seed = 0xFF009688,
    ),
    Green(
        value = "green",
        labelResource = R.string.settings_theme_green,
        seed = 0xFF4CAF50,
    ),
    Amber(
        value = "amber",
        labelResource = R.string.settings_theme_amber,
        seed = 0xFFFFC107,
    ),
    ;

    companion object {
        val default = Dynamic

        fun fromValue(value: String?): ThemePreset = entries.firstOrNull { it.value == value } ?: default
    }
}
