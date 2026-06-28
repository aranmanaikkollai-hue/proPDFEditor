package com.propdf.security.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.propdf.security.SecurityViewModel

/**
 * Security settings screen for configuring all Phase 8 features.
 */
@Composable
fun SecuritySettingsScreen(
    viewModel: SecurityViewModel,
    onNavigateToVault: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Security Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Biometric Status
        SecurityCard(
            icon = Icons.Default.Lock,
            title = "Biometric Authentication",
            description = when (uiState.biometricStatus) {
                com.propdf.security.biometric.BiometricAuthManager.BiometricStatus.AVAILABLE ->
                    "Ready to use"
                com.propdf.security.biometric.BiometricAuthManager.BiometricStatus.NOT_ENROLLED ->
                    "No fingerprints or face data enrolled"
                else -> "Not available on this device"
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Vault Mode
        SecurityCard(
            icon = Icons.Default.Lock,
            title = "Vault Mode",
            description = "Encrypt sensitive documents with AES-256-GCM"
        ) {
            OutlinedButton(onClick = onNavigateToVault) {
                Text("Open Vault")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Auto-Lock
        SecurityCard(
            icon = Icons.Default.AccessTime,
            title = "Auto-Lock",
            description = "Automatically lock after period of inactivity"
        ) {
            var timeoutMinutes by remember { mutableStateOf(5f) }
            Column {
                Text("${timeoutMinutes.toInt()} minutes")
                Slider(
                    value = timeoutMinutes,
                    onValueChange = { timeoutMinutes = it },
                    valueRange = 1f..60f,
                    steps = 59,
                    onValueChangeFinished = {
                        viewModel.setSessionTimeout(timeoutMinutes.toLong() * 60_000L)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Encrypted Backups
        SecurityCard(
            icon = Icons.Default.SaveAlt,
            title = "Encrypted Backups",
            description = "Export encrypted archives with password protection"
        ) {
            Row {
                OutlinedButton(onClick = onCreateBackup) {
                    Text("Create Backup")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onRestoreBackup) {
                    Text("Restore")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Manual Lock
        SecurityCard(
            icon = Icons.Default.Lock,
            title = "Lock Now",
            description = "Immediately lock the app"
        ) {
            OutlinedButton(onClick = { viewModel.lockSession() }) {
                Text("Lock Session")
            }
        }
    }
}

@Composable
private fun SecurityCard(
    icon: ImageVector,
    title: String,
    description: String,
    action: @Composable () -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            action()
        }
    }
}
