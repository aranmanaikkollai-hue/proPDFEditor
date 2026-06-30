package com.propdf.annotations.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.propdf.annotations.model.*
import com.propdf.annotations.model.Annotation
import java.text.SimpleDateFormat
import java.util.*

/**
 * Side panel showing all annotations with filtering, sorting, and management.
 * Supports search, type filtering, and bulk operations.
 */
@Composable
fun AnnotationListPanel(
    viewModel: AnnotationViewModel,
    pageIndex: Int,
    modifier: Modifier = Modifier
) {
    val allAnnotations = remember { derivedStateOf { viewModel.getAnnotationsForPage(pageIndex) } }.value
    val selectedAnnotations by viewModel.selectedAnnotations.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var filterType by remember { mutableStateOf<AnnotationType?>(null) }
    var sortBy by remember { mutableStateOf(SortBy.Z_INDEX) }

    val filteredAnnotations = remember(allAnnotations, searchQuery, filterType, sortBy) {
        allAnnotations
            .filter { annotation ->
                val matchesSearch = searchQuery.isBlank() || 
                    annotation.contents.contains(searchQuery, ignoreCase = true) ||
                    annotation.author.contains(searchQuery, ignoreCase = true) ||
                    annotation.type.name.contains(searchQuery, ignoreCase = true)
                val matchesType = filterType == null || annotation.type == filterType
                matchesSearch && matchesType
            }
            .sortedWith(
                when (sortBy) {
                    SortBy.Z_INDEX -> compareBy { it.zIndex }
                    SortBy.TYPE -> compareBy { it.type.name }
                    SortBy.CREATED -> compareByDescending { it.createdAt }
                    SortBy.MODIFIED -> compareByDescending { it.modifiedAt }
                }
            )
    }

    Surface(
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 280.dp, max = 360.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Text(
                text = "Annotations (${filteredAnnotations.size}/${allAnnotations.size})",
                style = MaterialTheme.typography.titleMedium
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Filter & Sort
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Type filter dropdown
                var showTypeMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { showTypeMenu = true }) {
                        Text(filterType?.name?.replace("_", " ") ?: "All Types")
                    }
                    DropdownMenu(
                        expanded = showTypeMenu,
                        onDismissRequest = { showTypeMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All Types") },
                            onClick = { filterType = null; showTypeMenu = false }
                        )
                        AnnotationType.values().forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name.replace("_", " ")) },
                                onClick = { filterType = type; showTypeMenu = false }
                            )
                        }
                    }
                }

                // Sort dropdown
                var showSortMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { showSortMenu = true }) {
                        Text(sortBy.label)
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        SortBy.values().forEach { sort ->
                            DropdownMenuItem(
                                text = { Text(sort.label) },
                                onClick = { sortBy = sort; showSortMenu = false }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bulk actions
            if (selectedAnnotations.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = { viewModel.bringToFront() }) {
                        Text("To Front")
                    }
                    TextButton(onClick = { viewModel.sendToBack() }) {
                        Text("To Back")
                    }
                    TextButton(
                        onClick = { viewModel.deleteSelected() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Delete")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Annotation list
            LazyColumn {
                items(filteredAnnotations, key = { it.id }) { annotation ->
                    AnnotationListItem(
                        annotation = annotation,
                        isSelected = selectedAnnotations.any { it.id == annotation.id },
                        onSelect = { viewModel.selectAnnotation(annotation, false) },
                        onDelete = { viewModel.deleteAnnotation(annotation) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AnnotationListItem(
    annotation: Annotation,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    val typeLabel = annotation.type.name.replace("_", " ")

    val contentPreview = when (annotation) {
        is TextAnnotation -> annotation.text.take(50)
        is StampAnnotation -> annotation.getDisplayText()
        else -> annotation.contents.take(50)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color/type indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .padding(end = 8.dp)
            ) {
                // Could show color indicator here
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                if (contentPreview.isNotBlank()) {
                    Text(
                        text = contentPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Text(
                    text = dateFormat.format(Date(annotation.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private enum class SortBy(val label: String) {
    Z_INDEX("Z-Index"),
    TYPE("Type"),
    CREATED("Created"),
    MODIFIED("Modified")
}
