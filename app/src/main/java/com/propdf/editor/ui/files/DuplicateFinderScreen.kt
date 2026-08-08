package com.propdf.editor.ui.files

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.utils.formatFileSize
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateFinderScreen(
    navController: NavController,
    viewModel: DuplicateFinderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Duplicate Finder") },
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
            if (uiState.duplicateGroups.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.Search,
                        title = "No duplicates found",
                        subtitle = "Your library is clean!"
                    )
                }
            } else {
                uiState.duplicateGroups.forEach { group ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "${group.size} duplicates",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    "Wasted space: ${formatFileSize(group.sumOf { it.fileSize })}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                    items(group) { doc ->
                        DuplicateItem(doc, onDelete = { viewModel.deleteDuplicate(doc) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateItem(
    document: PdfDocument,
    onDelete: () -> Unit
) {
    ListItem(
        headlineContent = { Text(document.displayName) },
        supportingContent = { Text(formatFileSize(document.fileSize)) },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    )
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}

@HiltViewModel
class DuplicateFinderViewModel @Inject constructor(
    private val coreDocumentRepository: com.propdf.core.domain.repository.DocumentRepository
) : androidx.lifecycle.ViewModel() {
    data class UiState(
        val duplicateGroups: List<List<PdfDocument>> = emptyList(),
        val isLoading: Boolean = false
    )
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadDuplicates()
    }

    private fun loadDuplicates() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val groups = try {
                coreDocumentRepository.findDuplicates().map { group ->
                    group.documents.map { it.toEditorModel() }
                }
            } catch (_: Exception) {
                emptyList()
            }
            _uiState.value = UiState(duplicateGroups = groups, isLoading = false)
        }
    }

    fun deleteDuplicate(document: PdfDocument) {
        viewModelScope.launch {
            try {
                coreDocumentRepository.moveToRecycleBin(document.id)
            } catch (_: Exception) {
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                duplicateGroups = _uiState.value.duplicateGroups
                    .map { group -> group.filterNot { it.id == document.id } }
                    .filter { it.size > 1 }
            )
        }
    }

    private fun com.propdf.core.domain.model.PdfDocument.toEditorModel(): PdfDocument = PdfDocument(
        id = id,
        uri = android.net.Uri.parse(uriString),
        displayName = displayName,
        fileSize = sizeBytes,
        dateModified = lastModified,
        dateAdded = lastModified,
        isFavorite = isFavorite,
        isDeleted = isInRecycleBin,
        pageCount = pageCount
    )
}
