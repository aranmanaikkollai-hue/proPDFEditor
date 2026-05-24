package com.propdf.editor.ui.recyclebin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.propdf.editor.ui.components.DocumentListItem
import com.propdf.editor.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(
    navController: NavController,
    viewModel: RecycleBinViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recycle Bin") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.files.isNotEmpty()) {
                        TextButton(onClick = { viewModel.emptyBin() }) {
                            Text("Empty All", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.isEmpty) {
            EmptyState(
                icon = Icons.Default.DeleteOutline,
                title = "Recycle Bin is Empty",
                subtitle = "Deleted files will appear here for 30 days"
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.files) { doc ->
                    DocumentListItem(
                        document = doc,
                        onClick = { },
                        onFavoriteClick = { },
                        onRestoreClick = { viewModel.restore(doc.id) },
                        onDeleteClick = { viewModel.permanentDelete(doc.id) }
                    )
                }
            }
        }
    }
}
