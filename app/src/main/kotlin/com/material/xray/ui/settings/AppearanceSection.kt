package com.material.xray.ui.settings

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.material.xray.ui.theme.presetColorScheme

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
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    item(key = "appearance") {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            SettingsSectionHeader(
                title = stringResource(R.string.settings_section_appearance),
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                showDivider = true,
            )
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                                ThemePresetSwatch(
                                    preset = preset,
                                    darkTheme = darkTheme,
                                    dynamicColorScheme = dynamicColorScheme,
                                    selected = preset == themePreset,
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

                    SettingsActionRow(
                        title = stringResource(R.string.settings_app_language_title),
                        subtitle = appLanguageName,
                        onClick = onAppLanguageClick,
                    )

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

                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_show_title_bar_logo),
                        checked = showTitleBarLogo,
                        onCheckedChange = onShowTitleBarLogoChange,
                    )

                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_floating_connect_button_title),
                        description = stringResource(R.string.settings_floating_connect_button_description),
                        checked = floatingConnectButton,
                        onCheckedChange = onFloatingConnectButtonChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemePresetSwatch(
    preset: ThemePreset,
    darkTheme: Boolean,
    dynamicColorScheme: ColorScheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accentColor = remember(preset, darkTheme, dynamicColorScheme) {
        if (preset == ThemePreset.Dynamic) {
            dynamicColorScheme.primary
        } else {
            presetColorScheme(preset, darkTheme).primary
        }
    }
    val iconTint = if (preset == ThemePreset.Dynamic) dynamicColorScheme.onPrimary else null
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
