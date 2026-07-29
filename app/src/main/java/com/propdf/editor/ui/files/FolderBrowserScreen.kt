package com.propdf.editor.ui.files

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.propdf.editor.data.local.dao.PdfDocumentDao
import com.propdf.editor.data.local.entity.PdfDocumentEntity
import com.propdf.editor.domain.model.Folder
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.repository.FolderRepository
import com.propdf.editor.utils.formatFileSize
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowserScreen(
    navController: NavController,
    folderId: String? = null,
    viewModel: FolderBrowserViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(folderId) {
        viewModel.load(folderId?.toLongOrNull())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.currentFolder?.name ?: "Folders") },
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
            if (folderId == null) {
                if (uiState.folders.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp)) {
                            Text(
                                "No folders yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(uiState.folders) { folder ->
                        ListItem(
                            headlineContent = { Text(folder.name) },
                            supportingContent = { Text("${folder.documentCount} document(s)") },
                            leadingContent = {
                                Icon(Icons.Default.Folder, null, tint = Color(folder.color))
                            },
                            modifier = Modifier.clickable {
                                navController.navigate("folder_browser/${folder.id}")
                            }
                        )
                    }
                }
            } else {
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
}

@HiltViewModel
class FolderBrowserViewModel @Inject constructor(
    private val folderRepository: FolderRepository,
    private val pdfDocumentDao: PdfDocumentDao
) : androidx.lifecycle.ViewModel() {
    data class UiState(
        val currentFolder: Folder? = null,
        val folders: List<Folder> = emptyList(),
        val documents: List<PdfDocument> = emptyList(),
        val isLoading: Boolean = false
    )
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    fun load(folderId: Long?) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            if (folderId == null) {
                folderRepository.getAllFolders().collectLatest { folders ->
                    _uiState.value = UiState(folders = folders, isLoading = false)
                }
            } else {
                val folder = folderRepository.getFolder(folderId)
                pdfDocumentDao.getFilesInFolder(folderId).collectLatest { entities ->
                    _uiState.value = UiState(
                        currentFolder = folder,
                        documents = entities.map { it.toDomain() },
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun PdfDocumentEntity.toDomain(): PdfDocument = PdfDocument(
        id = id,
        uri = android.net.Uri.parse(uri),
        displayName = displayName.ifBlank { fileName },
        fileSize = fileSize,
        dateModified = lastModified,
        dateAdded = createdAt,
        isFavorite = isFavorite,
        isDeleted = isDeleted,
        pageCount = pageCount
    )
}
