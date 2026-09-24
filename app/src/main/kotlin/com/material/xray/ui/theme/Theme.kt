package com.material.xray.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.material.xray.model.ThemePreset

@Composable
fun MaterialXrayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    preset: ThemePreset = ThemePreset.Dynamic,
    oledDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val baseColorScheme = when (preset) {
        ThemePreset.Dynamic -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (darkTheme) {
                    dynamicDarkColorScheme(context)
                } else {
                    dynamicLightColorScheme(context)
                }
            } else if (darkTheme) {
                DefaultBlueDarkColorScheme
            } else {
                DefaultBlueLightColorScheme
            }
        }
        ThemePreset.TokyoNight -> if (darkTheme) TokyoNightDarkColorScheme else TokyoNightLightColorScheme
        ThemePreset.Gruvbox -> if (darkTheme) GruvboxDarkColorScheme else GruvboxLightColorScheme
        ThemePreset.Nord -> if (darkTheme) NordDarkColorScheme else NordLightColorScheme
        ThemePreset.Catppuccin -> if (darkTheme) CatppuccinDarkColorScheme else CatppuccinLightColorScheme
        ThemePreset.Dracula -> if (darkTheme) DraculaDarkColorScheme else DraculaLightColorScheme
        ThemePreset.Solarized -> if (darkTheme) SolarizedDarkColorScheme else SolarizedLightColorScheme
        ThemePreset.RosePine -> if (darkTheme) RosePineDarkColorScheme else RosePineLightColorScheme
        ThemePreset.Everforest -> if (darkTheme) EverforestDarkColorScheme else EverforestLightColorScheme
    }
    val colorScheme = if (darkTheme && oledDark) {
        baseColorScheme.copy(
            background = Color.Black,
            surface = Color.Black,
        )
    } else {
        baseColorScheme
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

internal val DefaultBlueLightColorScheme = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF006A6A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9CF1F1),
    onSecondaryContainer = Color(0xFF002020),
)

internal val DefaultBlueDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF80D4D4),
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF004F4F),
    onSecondaryContainer = Color(0xFF9CF1F1),
)
