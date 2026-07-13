package com.propdf.editor.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.propdf.editor.ui.home.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    viewModel: HomeViewModel,
    onNavigateToViewer: (String) -> Unit,
    onNavigateToMerge: () -> Unit,
    onNavigateToSplit: () -> Unit,
    onNavigateToFolder: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Search, null) }
                    )
                },
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
            contentPadding = PaddingValues(16.dp)
        ) {
            val filtered = uiState.recentFiles.filter {
                it.displayName.contains(searchQuery, ignoreCase = true)
            }
            items(filtered) { file ->
                ListItem(
                    headlineContent = { Text(file.displayName) },
                    supportingContent = { Text("${file.sizeBytes} bytes") },
                    leadingContent = { Icon(Icons.Default.PictureAsPdf, null) },
                    modifier = Modifier.clickable { onNavigateToViewer(file.uriString) }
                )
            }
        }
    }
}
