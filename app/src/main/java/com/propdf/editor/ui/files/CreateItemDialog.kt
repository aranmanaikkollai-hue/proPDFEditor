package com.propdf.editor.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.propdf.editor.ui.theme.*

@Composable
fun CreateItemDialog(
    onDismiss: () -> Unit,
    onCreateFolder: (String, Color) -> Unit,
    onCreateDocument: () -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(pdf_blue) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Create New", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(pdf_blue, pdf_red, pdf_green, pdf_orange, pdf_purple, pdf_teal).forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    color = if (selectedColor == color) color else color.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedColor = color }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = {
                            if (folderName.isNotBlank()) {
                                onCreateFolder(folderName, selectedColor)
                            }
                            onDismiss()
                        }
                    ) { Text("Create") }
                }
            }
        }
    }
}
