package com.propdf.editor.ui.files

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.propdf.core.domain.model.DuplicateGroup
import com.propdf.core.domain.model.PdfDocument
import com.propdf.editor.ui.components.EmptyState
import com.propdf.editor.ui.components.LoadingOverlay
import com.propdf.editor.ui.home.formatFileSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateFinderScreen(
    onOpenDocument: (PdfDocument) -> Unit,
    onBack: () -> Unit,
    viewModel: DocumentManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf<DuplicateGroup?>(null) }
    
    LaunchedEffect(Unit) {
        viewModel.findDuplicates()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Duplicate Finder")
                        if (uiState.duplicateGroups.isNotEmpty()) {
                            Text(
                                "${uiState.duplicateGroups.size} groups found",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading && uiState.duplicateGroups.isEmpty() -> {
                    LoadingOverlay("Scanning for duplicates...")
                }
                uiState.duplicateGroups.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.ContentCopy,
                        title = "No duplicates found",
                        subtitle = "Your PDF collection is clean!"
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(uiState.duplicateGroups) { group ->
                            DuplicateGroupCard(
                                group = group,
                                onOpenDocument = onOpenDocument,
                                onDeleteGroup = { showDeleteConfirm = group }
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Delete confirmation dialog
    showDeleteConfirm?.let { group ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete Duplicates?") },
            text = {
                Text("This will keep one copy and delete ${group.documents.size - 1} duplicates, freeing ${formatFileSize(group.wastedBytes)}.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Keep first, delete rest
                        group.documents.drop(1).forEach { doc ->
                            viewModel.permanentlyDelete(doc.id)
                        }
                        showDeleteConfirm = null
                        viewModel.findDuplicates()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    onOpenDocument: (PdfDocument) -> Unit,
    onDeleteGroup: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ContentCopy,
                        null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${group.documents.size} copies",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                
                Text(
                    "Wasted: ${formatFileSize(group.wastedBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Documents
            group.documents.forEachIndexed { index, doc ->
                val isOriginal = index == 0
                
                ListItem(
                    headlineContent = {
                        Text(
                            doc.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = {
                        Text("${formatFileSize(doc.sizeBytes)} • ${doc.filePath}")
                    },
                    leadingContent = {
                        if (isOriginal) {
                            BadgedBox(
                                badge = {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ) {
                                        Text("Keep", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.PictureAsPdf, null)
                            }
                        } else {
                            Icon(
                                Icons.Default.DeleteOutline,
                                null,
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                            )
                        }
                    },
                    modifier = Modifier.clickable { onOpenDocument(doc) }
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = onDeleteGroup,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete Duplicates (Keep First)")
            }
        }
    }
}
