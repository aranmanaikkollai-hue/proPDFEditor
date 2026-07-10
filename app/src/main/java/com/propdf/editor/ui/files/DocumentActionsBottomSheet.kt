package com.propdf.editor.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.propdf.core.domain.model.PdfDocument
import com.propdf.editor.utils.formatFileSize

@Composable
fun DocumentActionsBottomSheet(
    document: PdfDocument,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = document.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
            Text(
                text = formatFileSize(document.fileSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            val actions = listOf(
                ActionItem("Rename", Icons.Default.DriveFileRenameOutline, onRename),
                ActionItem("Move", Icons.Default.DriveFileMove, onMove),
                ActionItem("Copy", Icons.Default.FileCopy, onCopy),
                ActionItem("Share", Icons.Default.Share, onShare),
                ActionItem(
                    if (document.isFavorite) "Remove Favorite" else "Add Favorite",
                    if (document.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    onFavorite
                ),
                ActionItem(
                    if (document.isHidden) "Unhide" else "Hide",
                    Icons.Default.VisibilityOff,
                    onHide
                ),
                ActionItem("Properties", Icons.Default.Info, onProperties),
                ActionItem("Delete", Icons.Default.Delete, onDelete, true)
            )
            
            actions.forEach { action ->
                ListItem(
                    headlineContent = { Text(action.title) },
                    leadingContent = { Icon(action.icon, null, tint = if (action.isDestructive) MaterialTheme.colorScheme.error else LocalContentColor.current) },
                    modifier = Modifier.clickable { 
                        action.onClick()
                        onDismiss()
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private data class ActionItem(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
    val isDestructive: Boolean = false
)
