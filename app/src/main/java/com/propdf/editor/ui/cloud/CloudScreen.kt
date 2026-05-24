package com.propdf.editor.ui.cloud

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.propdf.editor.domain.model.CloudProvider
import com.propdf.editor.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudScreen(navController: NavController) {
    var selectedProvider by remember { mutableStateOf<CloudProvider?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cloud Storage") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Connected Accounts", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
            }
            item {
                CloudProviderCard(
                    provider = "Google Drive",
                    icon = Icons.Default.Cloud,
                    color = androidx.compose.ui.graphics.Color(0xFF4285F4),
                    isConnected = false,
                    onConnect = { }
                )
            }
            item {
                CloudProviderCard(
                    provider = "OneDrive",
                    icon = Icons.Default.Cloud,
                    color = androidx.compose.ui.graphics.Color(0xFF0078D4),
                    isConnected = false,
                    onConnect = { }
                )
            }
            item {
                CloudProviderCard(
                    provider = "Dropbox",
                    icon = Icons.Default.Cloud,
                    color = androidx.compose.ui.graphics.Color(0xFF0061FF),
                    isConnected = false,
                    onConnect = { }
                )
            }
        }
    }
}

@Composable
fun CloudProviderCard(
    provider: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    isConnected: Boolean,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = provider, tint = color, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(provider, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isConnected) "Connected" else "Not connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onConnect) {
                Text(if (isConnected) "Sync" else "Connect")
            }
        }
    }
}
