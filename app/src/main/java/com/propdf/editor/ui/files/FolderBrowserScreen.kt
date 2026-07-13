package com.propdf.editor.ui.files

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
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.utils.formatFileSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowserScreen(
    navController: NavController,
    folderId: String? = null,
    viewModel: FolderBrowserViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.currentFolder?.name ?: "Folders") },
                navigationIcon = {
                    if (folderId != null) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp)
        ) {
            items(uiState.documents) { doc ->
                ListItem(
                    headlineContent = { Text(doc.displayName) },
                    supportingContent = { Text(formatFileSize(doc.fileSize)) },
                    leadingContent = { Icon(Icons.Default.PictureAsPdf, null) }
                )
            }
        }
    }
}

class FolderBrowserViewModel : androidx.lifecycle.ViewModel() {
    data class UiState(
        val currentFolder: com.propdf.editor.domain.model.Folder? = null,
        val documents: List<PdfDocument> = emptyList()
    )
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
}
