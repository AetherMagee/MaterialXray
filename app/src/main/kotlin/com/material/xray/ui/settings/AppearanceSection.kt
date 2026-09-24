package com.material.xray.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.material.xray.R
import com.material.xray.model.LauncherIcon
import com.material.xray.model.ThemePreset
import com.material.xray.ui.components.SelectableOptionRow
import com.material.xray.ui.components.SettingsSwitchRow
import com.material.xray.ui.text.labelResource
import com.material.xray.ui.theme.CatppuccinDarkColorScheme
import com.material.xray.ui.theme.CatppuccinLightColorScheme
import com.material.xray.ui.theme.DraculaDarkColorScheme
import com.material.xray.ui.theme.DraculaLightColorScheme
import com.material.xray.ui.theme.EverforestDarkColorScheme
import com.material.xray.ui.theme.EverforestLightColorScheme
import com.material.xray.ui.theme.GruvboxDarkColorScheme
import com.material.xray.ui.theme.GruvboxLightColorScheme
import com.material.xray.ui.theme.NordDarkColorScheme
import com.material.xray.ui.theme.NordLightColorScheme
import com.material.xray.ui.theme.RosePineDarkColorScheme
import com.material.xray.ui.theme.RosePineLightColorScheme
import com.material.xray.ui.theme.SolarizedDarkColorScheme
import com.material.xray.ui.theme.SolarizedLightColorScheme
import com.material.xray.ui.theme.TokyoNightDarkColorScheme
import com.material.xray.ui.theme.TokyoNightLightColorScheme

@Suppress("LongParameterList")
fun LazyListScope.appearanceSection(
    darkTheme: Boolean,
    dynamicColorScheme: ColorScheme,
    themeScrollState: ScrollState,
    themePreset: ThemePreset,
    oledDark: Boolean,
    appLanguageName: String,
    launcherIcon: LauncherIcon,
    showTitleBarLogo: Boolean,
    floatingConnectButton: Boolean,
    onThemePresetChange: (ThemePreset) -> Unit,
    onOledDarkChange: (Boolean) -> Unit,
    onAppLanguageClick: () -> Unit,
    onLauncherIconChange: (LauncherIcon) -> Unit,
    onShowTitleBarLogoChange: (Boolean) -> Unit,
    onFloatingConnectButtonChange: (Boolean) -> Unit,
) {
    item(key = "appearance_header") {
        SettingsSectionHeader(
            title = stringResource(R.string.settings_section_appearance),
            showDivider = true,
        )
    }

    item(key = "appearance_theme") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.settings_theme_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(themeScrollState)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemePreset.entries.forEach { preset ->
                    val accentColor = when (preset) {
                        ThemePreset.Dynamic -> dynamicColorScheme.primary
                        ThemePreset.TokyoNight -> if (darkTheme) {
                            TokyoNightDarkColorScheme.primary
                        } else {
                            TokyoNightLightColorScheme.primary
                        }
                        ThemePreset.Gruvbox -> if (darkTheme) {
                            GruvboxDarkColorScheme.primary
                        } else {
                            GruvboxLightColorScheme.primary
                        }
                        ThemePreset.Nord -> if (darkTheme) {
                            NordDarkColorScheme.primary
                        } else {
                            NordLightColorScheme.primary
                        }
                        ThemePreset.Catppuccin -> if (darkTheme) {
                            CatppuccinDarkColorScheme.primary
                        } else {
                            CatppuccinLightColorScheme.primary
                        }
                        ThemePreset.Dracula -> if (darkTheme) {
                            DraculaDarkColorScheme.primary
                        } else {
                            DraculaLightColorScheme.primary
                        }
                        ThemePreset.Solarized -> if (darkTheme) {
                            SolarizedDarkColorScheme.primary
                        } else {
                            SolarizedLightColorScheme.primary
                        }
                        ThemePreset.RosePine -> if (darkTheme) {
                            RosePineDarkColorScheme.primary
                        } else {
                            RosePineLightColorScheme.primary
                        }
                        ThemePreset.Everforest -> if (darkTheme) {
                            EverforestDarkColorScheme.primary
                        } else {
                            EverforestLightColorScheme.primary
                        }
                    }
                    ThemePresetSwatch(
                        preset = preset,
                        selected = preset == themePreset,
                        accentColor = accentColor,
                        iconTint = if (preset == ThemePreset.Dynamic) dynamicColorScheme.onPrimary else null,
                        onClick = { onThemePresetChange(preset) },
                    )
                }
            }
            if (darkTheme) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_oled_dark_title),
                    description = stringResource(R.string.settings_oled_dark_description),
                    checked = oledDark,
                    onCheckedChange = onOledDarkChange,
                )
            }
        }
    }

    item(key = "appearance_language") {
        SettingsActionRow(
            title = stringResource(R.string.settings_app_language_title),
            subtitle = appLanguageName,
            onClick = onAppLanguageClick,
        )
    }

    item(key = "appearance_icon") {
        SettingsNestedSection(title = stringResource(R.string.settings_app_icon_title)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LauncherIcon.entries.forEach { icon ->
                    SelectableOptionRow(
                        title = stringResource(icon.labelResource),
                        description = null,
                        selected = icon == launcherIcon,
                        onSelected = { onLauncherIconChange(icon) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }

    item(key = "appearance_title_bar_logo") {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_show_title_bar_logo),
            checked = showTitleBarLogo,
            onCheckedChange = onShowTitleBarLogoChange,
        )
    }

    item(key = "appearance_floating_connect_button") {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_floating_connect_button_title),
            description = stringResource(R.string.settings_floating_connect_button_description),
            checked = floatingConnectButton,
            onCheckedChange = onFloatingConnectButtonChange,
        )
    }
}

@Composable
private fun ThemePresetSwatch(
    preset: ThemePreset,
    selected: Boolean,
    accentColor: Color,
    iconTint: Color?,
    onClick: () -> Unit,
) {
    val label = stringResource(preset.labelResource)
    Column(
        modifier = Modifier
            .widthIn(min = 64.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics {
                contentDescription = label
                this.selected = selected
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accentColor),
                contentAlignment = Alignment.Center,
            ) {
                if (preset == ThemePreset.Dynamic) {
                    Icon(
                        imageVector = Icons.Outlined.Palette,
                        contentDescription = null,
                        tint = iconTint ?: MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
