package com.propdf.editor.ui.files

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.propdf.editor.ui.components.EmptyState
import com.propdf.editor.ui.home.formatFileSize
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowserScreen(
    initialPath: String = Environment.getExternalStorageDirectory().absolutePath,
    onOpenDocument: (com.propdf.core.domain.model.PdfDocument) -> Unit,
    onBack: () -> Unit,
    viewModel: DocumentManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var currentPath by remember { mutableStateOf(initialPath) }
    val pathStack = remember { mutableStateListOf<String>() }
    
    // Breadcrumb
    val breadcrumbs = remember(currentPath) {
        val parts = currentPath.split(File.separator).filter { it.isNotEmpty() }
        val result = mutableListOf<Pair<String, String>>()
        var built = ""
        parts.forEach { part ->
            built += File.separator + part
            result.add(part to built)
        }
        result
    }

    LaunchedEffect(currentPath) {
        viewModel.setViewType(DocumentViewType.FOLDER_BROWSER, currentPath)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Folders")
                        Text(
                            text = currentPath.substringAfterLast(File.separator).takeIf { it.isNotEmpty() } ?: "Root",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (pathStack.isNotEmpty()) {
                            currentPath = pathStack.removeAt(pathStack.lastIndex)
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Breadcrumbs
            ScrollableBreadcrumbs(
                breadcrumbs = breadcrumbs,
                onBreadcrumbClick = { path ->
                    pathStack.add(currentPath)
                    currentPath = path
                }
            )
            
            // Content
            if (uiState.documents.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.FolderOpen,
                    title = "Empty folder",
                    subtitle = "No PDFs in this location"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Subfolders
                    val subfolders = uiState.documents
                        .map { it.filePath.substringBeforeLast(File.separator) }
                        .distinct()
                        .filter { it != currentPath && it.startsWith(currentPath) }
                    
                    items(subfolders) { folder ->
                        val folderName = folder.substringAfterLast(File.separator)
                        val docCount = uiState.documents.count { it.filePath.startsWith(folder) }
                        
                        FolderItem(
                            name = folderName,
                            documentCount = docCount,
                            onClick = {
                                pathStack.add(currentPath)
                                currentPath = folder
                            }
                        )
                    }
                    
                    // Files in current folder
                    items(
                        uiState.documents.filter { 
                            it.filePath.substringBeforeLast(File.separator) == currentPath 
                        }
                    ) { doc ->
                        DocumentListItem(
                            document = doc,
                            isSelected = doc.id in uiState.selectedDocuments,
                            isSelectionMode = uiState.isSelectionMode,
                            viewMode = ViewMode.LIST,
                            onClick = { onOpenDocument(doc) },
                            onLongClick = { viewModel.toggleSelection(doc.id) },
                            onFavoriteClick = { viewModel.toggleFavorite(doc.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrollableBreadcrumbs(
    breadcrumbs: List<Pair<String, String>>,
    onBreadcrumbClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        breadcrumbs.forEachIndexed { index, (name, path) ->
            if (index > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = if (index == breadcrumbs.lastIndex) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onBreadcrumbClick(path) }
            )
        }
    }
}

@Composable
private fun FolderItem(
    name: String,
    documentCount: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Folder,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "$documentCount documents",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrocom.propdf.editor.ui.filesred.Filled.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
