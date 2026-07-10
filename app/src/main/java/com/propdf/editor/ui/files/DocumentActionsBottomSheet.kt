package com.propdf.editor.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.propdf.core.domain.model.PdfDocument

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentActionsBottomSheet(
    document: PdfDocument,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onFavorite: () -> Unit,
    onAddToCollection: () -> Unit,
    onAddTags: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Document header
            ListItem(
                headlineContent = { 
                    Text(
                        document.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                },
                supportingContent = { 
                    Text(
                        "${document.pageCount} pages • ${com.propdf.editor.ui.home.formatFileSize(document.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // Primary actions
            ActionItem(Icons.Default.Visibility, "Open", onOpen)
            ActionItem(Icons.Default.Share, "Share", onShare)
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // Organization
            ActionItem(
                if (document.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                if (document.isFavorite) "Remove from Favorites" else "Add to Favorites",
                onFavorite
            )
            ActionItem(Icons.Default.FolderCopy, "Add to Collection", onAddToCollection)
            ActionItem(Icons.Default.Label, "Add Tags", onAddTags)
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // File operations
            ActionItem(Icons.Default.DriveFileRenameOutline, "Rename", onRename)
            ActionItem(Icons.Default.DriveFileMove, "Move", onMove)
            ActionItem(Icons.Default.ContentCopy, "Copy", onCopy)
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // Other
            ActionItem(
                if (document.isHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                if (document.isHidden) "Unhide" else "Hide",
                onHide
            )
            ActionItem(Icons.Default.Info, "Properties", onProperties)
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // Danger zone
            ActionItem(
                Icons.Default.Delete,
                if (document.isInRecycleBin) "Delete Permanently" else "Move to Recycle Bin",
                onDelete,
                tint = MaterialTheme.colorScheme.error
            )
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            Icon(icon, null, tint = tint)
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
