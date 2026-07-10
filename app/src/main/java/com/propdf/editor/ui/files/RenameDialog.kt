package com.propdf.editor.ui.files

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.propdf.core.domain.model.PdfDocument

@Composable
fun RenameDialog(
    document: PdfDocument,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember { mutableStateOf(document.fileName) }
    var error by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
        title = { Text("Rename Document") },
        text = {
            Column {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { 
                        newName = it
                        error = null
                    },
                    label = { Text("New name") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newName.isBlank()) {
                        error = "Name cannot be empty"
                    } else if (!newName.endsWith(".pdf", ignoreCase = true)) {
                        error = "File must have .pdf extension"
                    } else {
                        onConfirm(newName)
                        onDismiss()
                    }
                }
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
