package com.material.xray.model

import androidx.annotation.StringRes
import com.material.xray.R

/** A user-selectable Material 3 color scheme, with dynamic color represented explicitly. */
enum class ThemePreset(
    val value: String,
    @param:StringRes val labelResource: Int,
) {
    Dynamic(
        value = "dynamic",
        labelResource = R.string.settings_theme_dynamic,
    ),
    TokyoNight(
        value = "tokyo_night",
        labelResource = R.string.settings_theme_tokyo_night,
    ),
    Gruvbox(
        value = "gruvbox",
        labelResource = R.string.settings_theme_gruvbox,
    ),
    Nord(
        value = "nord",
        labelResource = R.string.settings_theme_nord,
    ),
    Catppuccin(
        value = "catppuccin",
        labelResource = R.string.settings_theme_catppuccin,
    ),
    Dracula(
        value = "dracula",
        labelResource = R.string.settings_theme_dracula,
    ),
    Solarized(
        value = "solarized",
        labelResource = R.string.settings_theme_solarized,
    ),
    RosePine(
        value = "rose_pine",
        labelResource = R.string.settings_theme_rose_pine,
    ),
    Everforest(
        value = "everforest",
        labelResource = R.string.settings_theme_everforest,
    ),
    ;

    companion object {
        val default = Dynamic

        fun fromValue(value: String?): ThemePreset = entries.firstOrNull { it.value == value } ?: default
    }
}
