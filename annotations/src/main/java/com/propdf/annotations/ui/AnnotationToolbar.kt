package com.propdf.annotations.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.propdf.annotations.model.StrokeAnnotation

/**
 * Material 3 annotation toolbar with organized tool categories.
 * Responsive layout: horizontal scroll on phones, expanded on tablets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationToolbar(
    viewModel: AnnotationViewModel,
    isTablet: Boolean = false,
    modifier: Modifier = Modifier
) {
    val currentTool by viewModel.currentTool.collectAsState()
    val currentColor by viewModel.currentColor.collectAsState()
    val currentStrokeWidth by viewModel.currentStrokeWidth.collectAsState()
    val currentOpacity by viewModel.currentOpacity.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val undoDescription by viewModel.undoDescription.collectAsState()
    val redoDescription by viewModel.redoDescription.collectAsState()

    var showColorPicker by remember { mutableStateOf(false) }
    var showStrokeWidthSlider by remember { mutableStateOf(false) }
    var showOpacitySlider by remember { mutableStateOf(false) }

    Surface(
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.padding(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Drawing Tools
            ToolCategoryRow(
                title = "Drawing",
                tools = listOf(
                    ToolItem(AnnotationViewModel.AnnotationTool.PEN, "Pen", Icons.Default.Edit),
                    ToolItem(AnnotationViewModel.AnnotationTool.CALLIGRAPHY, "Calligraphy", Icons.Default.Brush),
                    ToolItem(AnnotationViewModel.AnnotationTool.MARKER, "Marker", Icons.Default.Highlight),
                    ToolItem(AnnotationViewModel.AnnotationTool.PENCIL, "Pencil", Icons.Default.Create),
                    ToolItem(AnnotationViewModel.AnnotationTool.ERASER, "Eraser", Icons.Default.AutoFixNormal)
                ),
                currentTool = currentTool,
                onToolSelected = { viewModel.setTool(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Text Markup
            ToolCategoryRow(
                title = "Markup",
                tools = listOf(
                    ToolItem(AnnotationViewModel.AnnotationTool.HIGHLIGHT, "Highlight", Icons.Default.HighlightAlt),
                    ToolItem(AnnotationViewModel.AnnotationTool.UNDERLINE, "Underline", Icons.Default.FormatUnderlined),
                    ToolItem(AnnotationViewModel.AnnotationTool.STRIKEOUT, "Strikeout", Icons.Default.FormatStrikethrough),
                    ToolItem(AnnotationViewModel.AnnotationTool.SQUIGGLY, "Squiggly", Icons.Default.Waves)
                ),
                currentTool = currentTool,
                onToolSelected = { viewModel.setTool(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Text Content
            ToolCategoryRow(
                title = "Text",
                tools = listOf(
                    ToolItem(AnnotationViewModel.AnnotationTool.TEXT, "Text", Icons.Default.TextFields),
                    ToolItem(AnnotationViewModel.AnnotationTool.STICKY_NOTE, "Note", Icons.Default.NoteAlt),
                    ToolItem(AnnotationViewModel.AnnotationTool.TEXTBOX, "Text Box", Icons.Default.TextSnippet),
                    ToolItem(AnnotationViewModel.AnnotationTool.FREE_TEXT, "Free Text", Icons.Default.Title)
                ),
                currentTool = currentTool,
                onToolSelected = { viewModel.setTool(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Shapes
            ToolCategoryRow(
                title = "Shapes",
                tools = listOf(
                    ToolItem(AnnotationViewModel.AnnotationTool.RECTANGLE, "Rectangle", Icons.Default.CropSquare),
                    ToolItem(AnnotationViewModel.AnnotationTool.CIRCLE, "Circle", Icons.Default.Circle),
                    ToolItem(AnnotationViewModel.AnnotationTool.LINE, "Line", Icons.Default.Remove),
                    ToolItem(AnnotationViewModel.AnnotationTool.ARROW, "Arrow", Icons.Default.ArrowForward),
                    ToolItem(AnnotationViewModel.AnnotationTool.POLYGON, "Polygon", Icons.Default.Hexagon),
                    ToolItem(AnnotationViewModel.AnnotationTool.CLOUD, "Cloud", Icons.Default.Cloud)
                ),
                currentTool = currentTool,
                onToolSelected = { viewModel.setTool(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Stamps & Signature
            ToolCategoryRow(
                title = "Stamps",
                tools = listOf(
                    ToolItem(AnnotationViewModel.AnnotationTool.STAMP, "Stamp", Icons.Default.Approval),
                    ToolItem(AnnotationViewModel.AnnotationTool.DATE_STAMP, "Date", Icons.Default.CalendarToday),
                    ToolItem(AnnotationViewModel.AnnotationTool.SIGNATURE, "Signature", Icons.Default.Draw)
                ),
                currentTool = currentTool,
                onToolSelected = { viewModel.setTool(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Actions Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Undo
                IconButton(
                    onClick = { viewModel.undo() },
                    enabled = canUndo
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = undoDescription ?: "Undo",
                        tint = if (canUndo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Redo
                IconButton(
                    onClick = { viewModel.redo() },
                    enabled = canRedo
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Redo,
                        contentDescription = redoDescription ?: "Redo",
                        tint = if (canRedo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                // Selection
                ToolButton(
                    tool = AnnotationViewModel.AnnotationTool.SELECTOR,
                    currentTool = currentTool,
                    onClick = { viewModel.setTool(AnnotationViewModel.AnnotationTool.SELECTOR) },
                    icon = Icons.Default.TouchApp,
                    label = "Select"
                )

                ToolButton(
                    tool = AnnotationViewModel.AnnotationTool.LASSO,
                    currentTool = currentTool,
                    onClick = { viewModel.setTool(AnnotationViewModel.AnnotationTool.LASSO) },
                    icon = Icons.Default.Gesture,
                    label = "Lasso"
                )

                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                // Properties
                IconButton(onClick = { showColorPicker = !showColorPicker }) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(currentColor)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                }

                IconButton(onClick = { showStrokeWidthSlider = !showStrokeWidthSlider }) {
                    Icon(Icons.Default.LineWeight, contentDescription = "Stroke Width")
                }

                IconButton(onClick = { showOpacitySlider = !showOpacitySlider }) {
                    Icon(Icons.Default.Opacity, contentDescription = "Opacity")
                }

                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                // Panels
                IconButton(onClick = { viewModel.toggleLayerPanel() }) {
                    Icon(Icons.Default.Layers, contentDescription = "Layers")
                }

                IconButton(onClick = { viewModel.toggleAnnotationList() }) {
                    Icon(Icons.Default.List, contentDescription = "Annotation List")
                }
            }

            // Property Panels
            if (showColorPicker) {
                ColorPicker(
                    selectedColor = currentColor,
                    onColorSelected = {
                        viewModel.setColor(it)
                        showColorPicker = false
                    },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (showStrokeWidthSlider) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Stroke: ${currentStrokeWidth.toInt()}px",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Slider(
                        value = currentStrokeWidth,
                        onValueChange = { viewModel.setStrokeWidth(it) },
                        valueRange = 0.5f..20f,
                        steps = 38
                    )
                }
            }

            if (showOpacitySlider) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Opacity: ${(currentOpacity * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Slider(
                        value = currentOpacity,
                        onValueChange = { viewModel.setOpacity(it) },
                        valueRange = 0.1f..1f,
                        steps = 8
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCategoryRow(
    title: String,
    tools: List<ToolItem>,
    currentTool: AnnotationViewModel.AnnotationTool,
    onToolSelected: (AnnotationViewModel.AnnotationTool) -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tools.forEach { tool ->
                ToolButton(
                    tool = tool.tool,
                    currentTool = currentTool,
                    onClick = { onToolSelected(tool.tool) },
                    icon = tool.icon,
                    label = tool.label
                )
            }
        }
    }
}

@Composable
private fun ToolButton(
    tool: AnnotationViewModel.AnnotationTool,
    currentTool: AnnotationViewModel.AnnotationTool,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String
) {
    val selected = tool == currentTool
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .widthIn(min = 48.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ColorPicker(
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = listOf(
        Color.Black, Color.DarkGray, Color.Gray,
        Color(0xFFF44336), Color(0xFFFF5722), Color(0xFFFF9800),
        Color(0xFFFFEB3B), Color(0xFFCDDC39), Color(0xFF4CAF50),
        Color(0xFF009688), Color(0xFF00BCD4), Color(0xFF2196F3),
        Color(0xFF3F51B5), Color(0xFF9C27B0), Color(0xFFE91E63)
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        colors.forEach { color ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (color == selectedColor) 3.dp else 1.dp,
                        color = if (color == selectedColor) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                    .clickable { onColorSelected(color) }
            )
        }
    }
}

private data class ToolItem(
    val tool: AnnotationViewModel.AnnotationTool,
    val label: String,
    val icon: ImageVector
)
