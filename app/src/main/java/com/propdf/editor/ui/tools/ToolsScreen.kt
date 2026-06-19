package com.propdf.editor.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(openLegacyTools: () -> Unit) {
    val tools = listOf("Merge PDFs", "Split PDF", "Compress PDF", "Watermark", "Page Numbers", "Encrypt PDF", "Extract Text")
    Scaffold(topBar = { TopAppBar(title = { Text("Tools") }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Offline PDF actions", style = MaterialTheme.typography.titleMedium) }
            items(tools) { tool ->
                Card(onClick = openLegacyTools, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    ListItem(headlineContent = { Text(tool) }, supportingContent = { Text("Runs locally on your device") }, leadingContent = { Icon(Icons.Default.Build, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchCardScreen(title: String, body: String, action: String, onLaunch: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(onClick = onLaunch, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                ListItem(headlineContent = { Text(action) }, supportingContent = { Text(body) }, leadingContent = { Icon(Icons.Default.Build, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) })
            }
        }
    }
}
