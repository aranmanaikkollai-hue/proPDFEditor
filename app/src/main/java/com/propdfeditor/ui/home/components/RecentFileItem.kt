package com.propdfeditor.ui.home.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.propdf.core.domain.model.RecentFile
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RecentFileItem(
    file: RecentFile,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(
                text = file.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = "${formatDate(file.lastOpened)} • ${file.pageCount} pages",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "PDF",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (file.isPinned) "Unpin" else "Pin") },
                        leadingIcon = {
                            Icon(
                                if (file.isPinned) Icons.Default.PushPin else Icons.Default.PushPin,
                                null
                            )
                        },
                        onClick = { expanded = false; onPin() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (file.isFavorite) "Remove Favorite" else "Favorite") },
                        leadingIcon = {
                            Icon(
                                if (file.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                null
                            )
                        },
                        onClick = { expanded = false; onFavorite() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { expanded = false; onDelete() }
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp
    )
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
