package com.propdf.annotations.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.propdf.annotations.layers.AnnotationLayer

/**
 * Layer management panel for annotation z-ordering and visibility.
 */
@Composable
fun LayerPanel(
    viewModel: AnnotationViewModel,
    modifier: Modifier = Modifier
) {
    val layers by viewModel.layers.collectAsState()
    val activeLayerId by viewModel.activeLayerId.collectAsState()

    var showAddLayerDialog by remember { mutableStateOf(false) }
    var newLayerName by remember { mutableStateOf("") }

    Surface(
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 280.dp, max = 360.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Layers (${layers.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = { showAddLayerDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Layer")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            LazyColumn {
                items(layers.sortedByDescending { it.zIndex }) { layer ->
                    LayerListItem(
                        layer = layer,
                        isActive = layer.id == activeLayerId,
                        onSelect = { viewModel.setActiveLayer(layer.id) },
                        onToggleVisibility = { 
                            viewModel.setLayerVisibility(layer.id, !layer.isVisible) 
                        },
                        onToggleLock = {
                            if (layer.isLocked) viewModel.layerManager.unlockLayer(layer.id)
                            else viewModel.layerManager.lockLayer(layer.id)
                        },
                        onDelete = { viewModel.deleteLayer(layer.id) }
                    )
                }
            }
        }
    }

    // Add Layer Dialog
    if (showAddLayerDialog) {
        AlertDialog(
            onDismissRequest = { showAddLayerDialog = false },
            title = { Text("New Layer") },
            text = {
                OutlinedTextField(
                    value = newLayerName,
                    onValueChange = { newLayerName = it },
                    label = { Text("Layer Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newLayerName.isNotBlank()) {
                            viewModel.createLayer(newLayerName)
                            newLayerName = ""
                            showAddLayerDialog = false
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddLayerDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun LayerListItem(
    layer: AnnotationLayer,
    isActive: Boolean,
    onSelect: () -> Unit,
    onToggleVisibility: () -> Unit,
    onToggleLock: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Visibility toggle
            IconButton(
                onClick = onToggleVisibility,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (layer.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (layer.isVisible) "Hide" else "Show",
                    modifier = Modifier.size(18.dp)
                )
            }

            // Lock toggle
            IconButton(
                onClick = onToggleLock,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (layer.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (layer.isLocked) "Locked" else "Unlocked",
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = layer.name,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${layer.annotations.size} annotations",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isActive) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Active",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
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
