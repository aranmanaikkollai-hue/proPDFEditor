package com.propdfeditor.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                ListItem(
                    headlineContent = { Text("Theme") },
                    supportingContent = { Text("System default") }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Storage") },
                    supportingContent = { Text("Manage cache and downloads") }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Backup & Sync") },
                    supportingContent = { Text("Cloud and local backups") }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("About") },
                    supportingContent = { Text("Version 3.0.0") }
                )
            }
        }
    }
}
