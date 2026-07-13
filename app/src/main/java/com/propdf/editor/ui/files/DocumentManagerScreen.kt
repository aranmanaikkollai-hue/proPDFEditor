package com.propdf.editor.ui.files

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
import com.propdf.editor.ui.components.DocumentListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentManagerScreen(
    navController: NavController,
    onOpenDocument: (String) -> Unit,
    onNavigateToViewer: () -> Unit,
    onNavigateToMerge: () -> Unit,
    onNavigateToSplit: () -> Unit,
    onNavigateToFolder: () -> Unit,
    viewModel: DocumentManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var isSelectionMode by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Documents") },
                actions = {
                    IconButton(onClick = { isSelectionMode = !isSelectionMode }) {
                        Icon(
                            if (isSelectionMode) Icons.Default.Close else Icons.Default.SelectAll,
                            contentDescription = "Select"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { /* Add document */ }) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp)
        ) {
            items(uiState.documents) { doc ->
                DocumentListItem(
                    document = doc,
                    onClick = { onOpenDocument(doc.uri.toString()) },
                    onFavoriteClick = { viewModel.toggleFavorite(doc.id) },
                    onDeleteClick = { viewModel.deleteDocument(doc.id) }
                )
            }
        }
    }
}
