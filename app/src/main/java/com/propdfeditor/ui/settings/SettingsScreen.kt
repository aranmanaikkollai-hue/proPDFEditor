package com.propdfeditor.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                SettingsSectionHeader("Appearance")
                ListItem(
                    headlineContent = { Text("Dark Mode") },
                    supportingContent = { Text(settings.darkMode.label) },
                    trailingContent = {
                        Switch(
                            checked = settings.darkMode == DarkMode.DARK,
                            onCheckedChange = { checked ->
                                viewModel.setDarkMode(
                                    if (checked) DarkMode.DARK else DarkMode.LIGHT
                                )
                            }
                        )
                    }
                )
                ListItem(
                    headlineContent = { Text("Dynamic Colors") },
                    supportingContent = { Text("Use system accent colors") },
                    trailingContent = {
                        Switch(
                            checked = settings.dynamicColors,
                            onCheckedChange = { viewModel.setDynamicColors(it) }
                        )
                    }
                )
            }

            item {
                SettingsSectionHeader("Viewer")
                ListItem(
                    headlineContent = { Text("Keep Screen On") },
                    supportingContent = { Text("While reading PDFs") },
                    trailingContent = {
                        Switch(
                            checked = settings.keepScreenOn,
                            onCheckedChange = { viewModel.setKeepScreenOn(it) }
                        )
                    }
                )
                ListItem(
                    headlineContent = { Text("Default Zoom") },
                    supportingContent = { Text("${(settings.defaultZoom * 100).toInt()}%") }
                )
                ListItem(
                    headlineContent = { Text("Scroll Direction") },
                    supportingContent = { Text(settings.scrollDirection.name) }
                )
            }

            item {
                SettingsSectionHeader("Storage")
                ListItem(
                    headlineContent = { Text("Clear Cache") },
                    supportingContent = { Text("Free up temporary files") },
                    leadingContent = { Icon(Icons.Default.CleaningServices, null) },
                    modifier = Modifier.clickable { viewModel.clearCache() }
                )
                ListItem(
                    headlineContent = { Text("Export Settings") },
                    leadingContent = { Icon(Icons.Default.Upload, null) },
                    modifier = Modifier.clickable { viewModel.exportSettings() }
                )
                ListItem(
                    headlineContent = { Text("Import Settings") },
                    leadingContent = { Icon(Icons.Default.Download, null) },
                    modifier = Modifier.clickable { viewModel.importSettings() }
                )
            }

            item {
                SettingsSectionHeader("Backup")
                ListItem(
                    headlineContent = { Text("Create Backup") },
                    supportingContent = { Text("Backup all data and settings") },
                    leadingContent = { Icon(Icons.Default.Backup, null) },
                    modifier = Modifier.clickable { viewModel.createBackup() }
                )
                ListItem(
                    headlineContent = { Text("Restore Backup") },
                    supportingContent = { Text("Restore from a previous backup") },
                    leadingContent = { Icon(Icons.Default.Restore, null) },
                    modifier = Modifier.clickable { viewModel.restoreBackup() }
                )
            }

            item {
                SettingsSectionHeader("About")
                ListItem(
                    headlineContent = { Text("Version") },
                    supportingContent = { Text("ProPDF Editor 3.0.0") }
                )
                ListItem(
                    headlineContent = { Text("Build") },
                    supportingContent = { Text("Release 2024.08.01") }
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
    )
}
