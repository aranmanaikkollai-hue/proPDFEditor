// annotations/src/main/java/com/propdf/annotations/ui/AnnotationToolbar.kt
package com.propdf.annotations.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import com.propdf.annotations.history.HistoryManager

/**
 * Annotation toolbar for tool selection, color picker, and undo/redo.
 */
@Composable
fun AnnotationToolbar(
    currentTool: AnnotationTool,
    onToolSelected: (AnnotationTool) -> Unit,
    currentColor: Color,
    onColorSelected: (Color) -> Unit,
    currentStrokeWidth: Float,
    onStrokeWidthChanged: (Float) -> Unit,
    historyManager: HistoryManager,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canUndo by historyManager.canUndo.collectAsState()
    val canRedo by historyManager.canRedo.collectAsState()

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Tool row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(AnnotationTool.values().toList()) { tool ->
                    ToolButton(
                        tool = tool,
                        isSelected = tool == currentTool,
                        onClick = { onToolSelected(tool) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Color and stroke row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Color palette
                val colors = listOf(
                    Color.Black, Color.Red, Color.Blue, Color.Green,
                    Color.Yellow, Color.Magenta, Color.Cyan, Color.White
                )
                colors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable { onColorSelected(color) }
                            .then(
                                if (color == currentColor)
                                    Modifier.padding(2.dp)
                                else
                                    Modifier
                            )
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Stroke width slider
                Text(
                    text = "${currentStrokeWidth.toInt()}px",
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = currentStrokeWidth,
                    onValueChange = onStrokeWidthChanged,
                    valueRange = 1f..20f,
                    modifier = Modifier.width(120.dp)
                )

                Spacer(modifier = Modifier.weight(1f))

                // Undo/Redo
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(Icons.Default.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(Icons.Default.Redo, contentDescription = "Redo")
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    tool: AnnotationTool,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val icon = when (tool) {
        AnnotationTool.PEN -> Icons.Default.Edit
        AnnotationTool.HIGHLIGHTER -> Icons.Default.Highlight
        AnnotationTool.ERASER -> Icons.Default.Delete
        AnnotationTool.SELECTOR -> Icons.Default.TouchApp
        AnnotationTool.TEXT -> Icons.Default.TextFields
        AnnotationTool.SHAPE_RECTANGLE -> Icons.Default.CropSquare
        AnnotationTool.SHAPE_CIRCLE -> Icons.Default.Circle
        AnnotationTool.SHAPE_ARROW -> Icons.Default.ArrowForward
    }

    val label = when (tool) {
        AnnotationTool.PEN -> "Pen"
        AnnotationTool.HIGHLIGHTER -> "Highlight"
        AnnotationTool.ERASER -> "Eraser"
        AnnotationTool.SELECTOR -> "Select"
        AnnotationTool.TEXT -> "Text"
        AnnotationTool.SHAPE_RECTANGLE -> "Rect"
        AnnotationTool.SHAPE_CIRCLE -> "Circle"
        AnnotationTool.SHAPE_ARROW -> "Arrow"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = if (isSelected) MaterialTheme.colorScheme.primary else LocalContentColor.current)
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}
