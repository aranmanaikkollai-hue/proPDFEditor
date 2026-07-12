package com.propdf.editor.ui.files

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.ui.home.formatFileSize
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DocumentPropertiesDialog(
    document: PdfDocument,
    onDismiss: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, contentDescription = null) },
        title = { Text("Document Properties") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PropertyRow("Name", document.displayName)
                PropertyRow("Size", formatFileSize(document.fileSize))
                PropertyRow("Modified", dateFormat.format(Date(document.dateModified)))
                PropertyRow("Added", dateFormat.format(Date(document.dateAdded)))
                PropertyRow("Pages", document.pageCount.toString())
                PropertyRow("Category", document.category.displayName)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
