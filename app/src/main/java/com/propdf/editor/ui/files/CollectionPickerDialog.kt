package com.propdf.editor.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.propdf.core.domain.model.DocumentCollection

@Composable
fun CollectionPickerDialog(
    collections: List<DocumentCollection>,
    currentCollectionId: Long?,
    onDismiss: () -> Unit,
    onSelectCollection: (Long?) -> Unit,
    onCreateCollection: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.CollectionsBookmark, null) },
        title = { Text("Select Collection") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    ListItem(
                        headlineContent = { Text("None") },
                        leadingContent = {
                            Icon(Icons.Default.Clear, null)
                        },
                        modifier = Modifier.clickable { 
                            onSelectCollection(null)
                            onDismiss()
                        }
                    )
                }
                
                items(collections) { collection ->
                    ListItem(
                        headlineContent = { Text(collection.name) },
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(collection.color))
                            )
                        },
                        trailingContent = if (collection.id == currentCollectionId) {
                            { Icon(Icons.Default.Check, null) }
                        } else null,
                        modifier = Modifier.clickable {
                            onSelectCollection(collection.id)
                            onDismiss()
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreateCollection) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Collection")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
