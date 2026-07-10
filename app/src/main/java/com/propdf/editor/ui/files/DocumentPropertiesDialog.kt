package com.propdf.editor.ui.files

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.propdf.core.domain.model.PdfDocument
import com.propdf.editor.ui.home.formatFileSize
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
        title = { Text("Document Properties") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PropertyItem("Name", document.displayName)
                PropertyItem("File Name", document.fileName)
                PropertyItem("Path", document.filePath)
                PropertyItem("Size", formatFileSize(document.sizeBytes))
                PropertyItem("Pages", document.pageCount.toString())
                PropertyItem("Format", document.extension.uppercase())
                
                document.metadataTitle?.let { PropertyItem("Title", it) }
                document.metadataAuthor?.let { PropertyItem("Author", it) }
                document.metadataSubject?.let { PropertyItem("Subject", it) }
                document.metadataKeywords?.let { PropertyItem("Keywords", it) }
                
                PropertyItem("Modified", dateFormat.format(Date(document.lastModified)))
                document.lastOpened?.let {
                    PropertyItem("Last Opened", dateFormat.format(Date(it)))
                }
                document.metadataCreationDate?.let {
                    PropertyItem("Created", dateFormat.format(Date(it)))
                }
                
                document.checksum?.let { PropertyItem("Checksum", it.take(16) + "...") }
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
private fun PropertyItem(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
