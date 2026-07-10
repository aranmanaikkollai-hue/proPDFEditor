package com.propdf.editor.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.propdf.editor.utils.formatFileSize

@Composable
fun FolderBrowser(
    currentPath: String?,
    onFolderClick: (String) -> Unit
) {
    val folders = remember(currentPath) {
        // TODO: Get actual folders from repository
        listOf(
            FolderItem("Documents", "/storage/emulated/0/Documents", 12, 45_000_000),
            FolderItem("Downloads", "/storage/emulated/0/Download", 8, 23_000_000),
            FolderItem("PDFs", "/storage/emulated/0/PDFs", 24, 120_000_000)
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Breadcrumb
        currentPath?.let { path ->
            TextButton(onClick = { /* Navigate up */ }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Back")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(folders) { folder ->
                FolderCard(folder = folder, onClick = { onFolderClick(folder.path) })
            }
        }
    }
}

@Composable
private fun FolderCard(folder: FolderItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${folder.documentCount} files · ${formatFileSize(folder.totalSize)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

data class FolderItem(
    val name: String,
    val path: String,
    val documentCount: Int,
    val totalSize: Long
)
