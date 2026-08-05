package com.propdfeditor.ui.settings

import androidx.compose.foundation.clickable
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
import com.propdf.editor.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val actionResult by viewModel.actionResult.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(actionResult) {
        actionResult?.let { result ->
            scope.launch {
                snackbarHostState.showSnackbar(result.message)
            }
            viewModel.consumeActionResult()
        }
    }

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
                SettingsSectionHeader("Storage")
                ListItem(
                    headlineContent = { Text("Clear Cache") },
                    supportingContent = { Text("Free up temporary files") },
                    leadingContent = { Icon(Icons.Default.CleaningServices, null) },
                    modifier = Modifier.clickable { viewModel.clearCache() }
                )
                ListItem(
                    headlineContent = { Text("Empty Recycle Bin") },
                    supportingContent = { Text("Permanently delete everything in the recycle bin") },
                    leadingContent = { Icon(Icons.Default.Delete, null) },
                    modifier = Modifier.clickable { viewModel.emptyRecycleBin() }
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
