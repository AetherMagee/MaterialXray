package com.material.xray.feature.settings

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.material.xray.core.ui.R
import com.material.xray.core.ui.components.FocusHighlight
import com.material.xray.core.ui.components.rememberSystemState

@Composable
internal fun AlwaysOnVpnSetting(rootServiceActive: Boolean) {
    val context = LocalContext.current
    val alwaysOn = rememberSystemState(::isAlwaysOnVpnEnabled).value
    val enabled = !rootServiceActive

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(
                interactionSource = null,
                indication = FocusHighlight,
                role = Role.Button,
                enabled = enabled,
                onClick = { context.openVpnSettings() },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_always_on_vpn),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            )
            Text(
                stringResource(
                    if (enabled) R.string.settings_always_on_vpn_description else R.string.settings_always_on_vpn_root_unavailable,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
            )
        }
        Switch(checked = alwaysOn, onCheckedChange = null, enabled = false)
    }
}

internal fun isAlwaysOnVpnEnabled(context: Context): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // This getter queries the calling app's UID, without needing a running service or context.
        VpnService().isAlwaysOn
    } else {
        Settings.Secure.getString(context.contentResolver, "always_on_vpn_app") == context.packageName
    }
}.getOrDefault(false)

private fun Context.openVpnSettings() {
    // Android exposes the VPN list, but its per-app management screen is not exported.
    runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
        .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
}
