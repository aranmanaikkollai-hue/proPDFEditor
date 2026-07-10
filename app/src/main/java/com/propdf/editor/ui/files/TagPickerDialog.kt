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
import com.propdf.core.domain.model.DocumentTag

@Composable
fun TagPickerDialog(
    tags: List<DocumentTag>,
    selectedTagIds: List<Long>,
    onDismiss: () -> Unit,
    onTagsSelected: (List<Long>) -> Unit,
    onCreateTag: () -> Unit
) {
    var localSelection by remember { mutableStateOf(selectedTagIds) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Label, null) },
        title = { Text("Select Tags") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(tags) { tag ->
                    val isSelected = tag.id in localSelection
                    
                    ListItem(
                        headlineContent = { Text(tag.name) },
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(tag.color))
                            )
                        },
                        trailingContent = {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    localSelection = if (isSelected) {
                                        localSelection - tag.id
                                    } else {
                                        localSelection + tag.id
                                    }
                                }
                            )
                        },
                        modifier = Modifier.clickable {
                            localSelection = if (isSelected) {
                                localSelection - tag.id
                            } else {
                                localSelection + tag.id
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onTagsSelected(localSelection)
                    onDismiss()
                }
            ) {
                Text("Apply (${localSelection.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onCreateTag) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Tag")
            }
        }
    )
}
