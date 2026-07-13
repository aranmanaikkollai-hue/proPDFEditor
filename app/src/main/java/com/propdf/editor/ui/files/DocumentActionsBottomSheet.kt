package com.propdf.editor.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.utils.formatFileSize
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentActionsBottomSheet(
    document: PdfDocument,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onFavorite: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(document.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                "${formatFileSize(document.fileSize)} · Modified ${dateFormat.format(Date(document.dateModified))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            ListItem(
                headlineContent = { Text("Share") },
                leadingContent = { Icon(Icons.Default.Share, null) },
                modifier = Modifier.clickable { onShare(); onDismiss() }
            )
            ListItem(
                headlineContent = { Text(if (document.isFavorite) "Remove from favorites" else "Add to favorites") },
                leadingContent = { Icon(if (document.isFavorite) Icons.Default.Star else Icons.Default.StarBorder, null) },
                modifier = Modifier.clickable { onFavorite(); onDismiss() }
            )
            ListItem(
                headlineContent = { Text("Rename") },
                leadingContent = { Icon(Icons.Default.Edit, null) },
                modifier = Modifier.clickable { onRename(); onDismiss() }
            )
            ListItem(
                headlineContent = { Text("Move") },
                leadingContent = { Icon(Icons.Default.DriveFileMove, null) },
                modifier = Modifier.clickable { onMove(); onDismiss() }
            )
            ListItem(
                headlineContent = { Text("Delete") },
                leadingContent = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { onDelete(); onDismiss() }
            )
        }
    }
}
