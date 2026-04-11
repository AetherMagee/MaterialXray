package com.materialxray.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val tunName by viewModel.tunName.collectAsState()
    val dnsServers by viewModel.dnsServers.collectAsState()
    val autoConnect by viewModel.autoConnect.collectAsState()

    var editingTunName by remember(tunName) { mutableStateOf(tunName) }
    var editingDns by remember(dnsServers) { mutableStateOf(dnsServers) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackup(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Network", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = editingTunName,
                onValueChange = { editingTunName = it },
                label = { Text("TUN Interface Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Default: xray0") },
            )
            if (editingTunName != tunName) {
                TextButton(onClick = { viewModel.setTunName(editingTunName) }) { Text("Save") }
            }

            OutlinedTextField(
                value = editingDns,
                onValueChange = { editingDns = it },
                label = { Text("DNS Servers") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Comma-separated, e.g. 1.1.1.1,8.8.8.8") },
            )
            if (editingDns != dnsServers) {
                TextButton(onClick = { viewModel.setDnsServers(editingDns) }) { Text("Save") }
            }

            HorizontalDivider()
            Text("Startup", style = MaterialTheme.typography.titleMedium)

            ListItem(
                headlineContent = { Text("Auto-connect on boot") },
                supportingContent = { Text("Reconnect to last server after device restart") },
                trailingContent = { Switch(checked = autoConnect, onCheckedChange = { viewModel.setAutoConnect(it) }) },
            )

            HorizontalDivider()
            Text("Data", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("materialxray-backup.json") }) { Text("Export") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) { Text("Import") }
            }

            HorizontalDivider()
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("MaterialXray v1.0.0", style = MaterialTheme.typography.bodyMedium)
            Text("xray-core v26.3.27", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
