package com.propdf.editor.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.propdf.core.domain.model.DuplicateGroup
import com.propdf.editor.utils.formatFileSize

@Composable
fun DuplicateFinderScreen(
    duplicateGroups: List<DuplicateGroup>,
    onFindDuplicates: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (duplicateGroups.isEmpty()) {
            EmptyState(
                icon = Icons.Default.FileCopy,
                title = "No Duplicates Found",
                message = "Tap the button below to scan for duplicate files",
                action = {
                    Button(onClick = onFindDuplicates) {
                        Icon(Icons.Default.Search, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan for Duplicates")
                    }
                }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(duplicateGroups) { group ->
                    DuplicateGroupCard(group = group)
                }
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(group: DuplicateGroup) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${group.documents.size} duplicates",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Wasted: ${formatFileSize(group.wastedSpace)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            group.documents.take(if (expanded) group.documents.size else 2).forEach { doc ->
                ListItem(
                    headlineContent = { Text(doc.displayName) },
                    supportingContent = { Text(formatFileSize(doc.fileSize)) },
                    leadingContent = { Icon(Icons.Default.InsertDriveFile, null) }
                )
            }
            
            if (group.documents.size > 2) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(if (expanded) "Show Less" else "Show ${group.documents.size - 2} More")
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    action: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(16.dp))
        action()
    }
}
