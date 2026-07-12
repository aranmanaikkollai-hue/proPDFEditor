package com.propdf.editor.ui.recent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.propdf.editor.ui.home.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentFilesScreen(
    navController: NavController,
    viewModel: HomeViewModel,
    onNavigateToViewer: (String) -> Unit,
    onNavigateToMerge: () -> Unit,
    onNavigateToSplit: () -> Unit,
    onNavigateToFolder: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recent Files") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(uiState.recentFiles) { file ->
                ListItem(
                    headlineContent = { Text(file.displayName) },
                    supportingContent = { Text("${file.fileSizeBytes} bytes · ${file.pageCount} pages") },
                    leadingContent = { Icon(Icons.Default.PictureAsPdf, null) },
                    modifier = Modifier.clickable { onNavigateToViewer(file.uri) }
                )
            }
        }
    }
}
