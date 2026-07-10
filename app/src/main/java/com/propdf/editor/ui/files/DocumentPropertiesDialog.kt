package com.propdf.editor.ui.files

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.propdf.core.domain.model.PdfDocument
import com.propdf.editor.utils.formatFileSize
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DocumentPropertiesDialog(
    document: PdfDocument,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, null) },
        title = { Text("Properties") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PropertyRow("Name", document.displayName)
                PropertyRow("Size", formatFileSize(document.fileSize))
                PropertyRow("Path", document.filePath ?: "Unknown")
                PropertyRow("Modified", dateFormat.format(Date(document.dateModified)))
                PropertyRow("Created", dateFormat.format(Date(document.dateAdded)))
                PropertyRow("Pages", document.pageCount.toString())
                PropertyRow("Favorite", if (document.isFavorite) "Yes" else "No")
                PropertyRow("Hidden", if (document.isHidden) "Yes" else "No")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
