package com.material.xray.model

import androidx.annotation.StringRes
import com.material.xray.R

/** A user-selectable Material 3 color scheme, with dynamic color represented explicitly. */
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
    TokyoNight(
        value = "tokyo_night",
        labelResource = R.string.settings_theme_tokyo_night,
        seed = 0xFF7AA2F7,
    ),
    Gruvbox(
        value = "gruvbox",
        labelResource = R.string.settings_theme_gruvbox,
        seed = 0xFF83A598,
    ),
    Nord(
        value = "nord",
        labelResource = R.string.settings_theme_nord,
        seed = 0xFF88C0D0,
    ),
    Catppuccin(
        value = "catppuccin",
        labelResource = R.string.settings_theme_catppuccin,
        seed = 0xFF89B4FA,
    ),
    Dracula(
        value = "dracula",
        labelResource = R.string.settings_theme_dracula,
        seed = 0xFFBD93F9,
    ),
    Solarized(
        value = "solarized",
        labelResource = R.string.settings_theme_solarized,
        seed = 0xFF268BD2,
    ),
    RosePine(
        value = "rose_pine",
        labelResource = R.string.settings_theme_rose_pine,
        seed = 0xFFC4A7E7,
    ),
    Everforest(
        value = "everforest",
        labelResource = R.string.settings_theme_everforest,
        seed = 0xFFA7C080,
    ),
    ;

    companion object {
        val default = Dynamic

        fun fromValue(value: String?): ThemePreset = entries.firstOrNull { it.value == value } ?: default
    }
}
