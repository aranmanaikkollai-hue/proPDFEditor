package com.propdf.annotations.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import java.io.File

/**
 * Compact, collapsible Material 3 annotation toolbar.
 *
 * Default state shows two slim rows (a single scrollable icon strip of every tool, plus an
 * actions row) so it doesn't dominate the screen. Tapping the expand chevron reveals labeled
 * tool categories for discoverability. Save/Export/Import/Flatten/Burn live in the overflow
 * menu and give real UI feedback (via [snackbarHostState]) instead of failing silently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationToolbar(
    viewModel: AnnotationViewModel,
    isTablet: Boolean = false,
    snackbarHostState: SnackbarHostState? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val currentTool by viewModel.currentTool.collectAsState()
    val currentColor by viewModel.currentColor.collectAsState()
    val currentStrokeWidth by viewModel.currentStrokeWidth.collectAsState()
    val currentOpacity by viewModel.currentOpacity.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val undoDescription by viewModel.undoDescription.collectAsState()
    val redoDescription by viewModel.redoDescription.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val documentPath by viewModel.currentDocumentPath.collectAsState()

    var expanded by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showStrokeWidthSlider by remember { mutableStateOf(false) }
    var showOpacitySlider by remember { mutableStateOf(false) }

    // Surface save/export feedback as Snackbars instead of it happening silently.
    // ExportSuccess additionally triggers copying the generated cache file to wherever
    // the user picked via the SAF launchers below (pendingSaveTarget).
    var pendingSaveTarget by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(snackbarHostState) {
        if (snackbarHostState == null) return@LaunchedEffect
        viewModel.saveEvent.collectLatest { event ->
            when (event) {
                is AnnotationViewModel.SaveEvent.Success ->
                    snackbarHostState.showSnackbar("Saved ${event.annotationCount} annotation(s)")
                is AnnotationViewModel.SaveEvent.ExportSuccess -> {
                    val target = pendingSaveTarget
                    pendingSaveTarget = null
                    if (target != null) {
                        val copied = try {
                            context.contentResolver.openOutputStream(target)?.use { out ->
                                File(event.path).inputStream().use { it.copyTo(out) }
                            }
                            true
                        } catch (e: Exception) {
                            false
                        }
                        snackbarHostState.showSnackbar(
                            if (copied) "Saved" else "Generated, but couldn't write to the chosen location"
                        )
                    } else {
                        snackbarHostState.showSnackbar("Saved to ${event.path}")
                    }
                }
                is AnnotationViewModel.SaveEvent.Failure -> {
                    pendingSaveTarget = null
                    snackbarHostState.showSnackbar("Failed: ${event.message}")
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}.json")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        viewModel.importAnnotations(tmp)
    }

    fun outputFile(suffix: String, extension: String): File {
        val base = documentPath.takeIf { it.isNotBlank() }?.let { File(it).nameWithoutExtension } ?: "document"
        val dir = context.getExternalFilesDir(null) ?: context.cacheDir
        return File(dir, "${base}_$suffix.$extension")
    }

    fun defaultName(suffix: String, extension: String): String {
        val base = documentPath.takeIf { it.isNotBlank() }?.let { File(it).nameWithoutExtension } ?: "document"
        return "${base}_$suffix.$extension"
    }

    // "Save As" pickers: user chooses the destination FIRST (e.g. Downloads), then we
    // generate the file and copy it there — this is what makes the result easy to find,
    // instead of landing in an app-private folder nobody can browse to.
    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            pendingSaveTarget = uri
            viewModel.exportAnnotations(outputFile("annotations", "json"))
        }
    }
    val flattenPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null) {
            pendingSaveTarget = uri
            viewModel.flattenAnnotations(outputFile("flattened", "pdf"))
        }
    }
    val burnPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null) {
            pendingSaveTarget = uri
            viewModel.burnAnnotationsIntoPdf(outputFile("burned", "pdf"))
        }
    }

    Surface(
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.padding(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {

            // ---- Compact row: expand toggle + every tool as an icon-only strip ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse tools" else "Expand tools"
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ALL_TOOLS.forEachIndexed { index, tool ->
                        if (index > 0 && CATEGORY_BOUNDARIES.contains(index)) {
                            VerticalDivider(modifier = Modifier.height(28.dp).padding(horizontal = 2.dp))
                        }
                        CompactToolButton(
                            tool = tool.tool,
                            currentTool = currentTool,
                            icon = tool.icon,
                            label = tool.label,
                            onClick = { viewModel.setTool(tool.tool) }
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    ToolCategoryRow("Drawing", DRAWING_TOOLS, currentTool) { viewModel.setTool(it) }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    ToolCategoryRow("Markup", MARKUP_TOOLS, currentTool) { viewModel.setTool(it) }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    ToolCategoryRow("Text", TEXT_TOOLS, currentTool) { viewModel.setTool(it) }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    ToolCategoryRow("Shapes", SHAPE_TOOLS, currentTool) { viewModel.setTool(it) }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    ToolCategoryRow("Stamps", STAMP_TOOLS, currentTool) { viewModel.setTool(it) }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ---- Actions row: undo/redo, select, lasso, color, save, overflow ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.undo() }, enabled = canUndo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = undoDescription ?: "Undo",
                        tint = if (canUndo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { viewModel.redo() }, enabled = canRedo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Redo,
                        contentDescription = redoDescription ?: "Redo",
                        tint = if (canRedo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

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

                IconButton(onClick = { showColorPicker = !showColorPicker }) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(currentColor)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                }

                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                // Explicit Save action with visible progress + feedback
                IconButton(onClick = { viewModel.forceSave() }, enabled = !isExporting) {
                    if (isExporting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                }

                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Stroke width") },
                            leadingIcon = { Icon(Icons.Default.LineWeight, null) },
                            onClick = {
                                showMoreMenu = false
                                showStrokeWidthSlider = !showStrokeWidthSlider
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Opacity") },
                            leadingIcon = { Icon(Icons.Default.Opacity, null) },
                            onClick = {
                                showMoreMenu = false
                                showOpacitySlider = !showOpacitySlider
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Layers") },
                            leadingIcon = { Icon(Icons.Default.Layers, null) },
                            onClick = {
                                showMoreMenu = false
                                viewModel.toggleLayerPanel()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Annotation list") },
                            leadingIcon = { Icon(Icons.Default.List, null) },
                            onClick = {
                                showMoreMenu = false
                                viewModel.toggleAnnotationList()
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Export annotations") },
                            leadingIcon = { Icon(Icons.Default.Upload, null) },
                            onClick = {
                                showMoreMenu = false
                                exportJsonLauncher.launch(defaultName("annotations", "json"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Import annotations") },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = {
                                showMoreMenu = false
                                importLauncher.launch(arrayOf("application/json", "*/*"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Flatten annotations") },
                            leadingIcon = { Icon(Icons.Default.Layers, null) },
                            onClick = {
                                showMoreMenu = false
                                flattenPdfLauncher.launch(defaultName("flattened", "pdf"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Burn into PDF") },
                            leadingIcon = { Icon(Icons.Default.LocalFireDepartment, null) },
                            onClick = {
                                showMoreMenu = false
                                burnPdfLauncher.launch(defaultName("burned", "pdf"))
                            }
                        )
                    }
                }
            }

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
                    Text("Stroke: ${currentStrokeWidth.toInt()}px", style = MaterialTheme.typography.labelSmall)
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
                    Text("Opacity: ${(currentOpacity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
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
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .widthIn(min = 48.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Icon-only variant for the compact single-row tool strip (no label, smaller footprint). */
@Composable
private fun CompactToolButton(
    tool: AnnotationViewModel.AnnotationTool,
    currentTool: AnnotationViewModel.AnnotationTool,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val selected = tool == currentTool
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(6.dp)
            .size(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp)
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

private val DRAWING_TOOLS = listOf(
    ToolItem(AnnotationViewModel.AnnotationTool.PEN, "Pen", Icons.Default.Edit),
    ToolItem(AnnotationViewModel.AnnotationTool.CALLIGRAPHY, "Calligraphy", Icons.Default.Brush),
    ToolItem(AnnotationViewModel.AnnotationTool.MARKER, "Marker", Icons.Default.Highlight),
    ToolItem(AnnotationViewModel.AnnotationTool.PENCIL, "Pencil", Icons.Default.Create),
    ToolItem(AnnotationViewModel.AnnotationTool.ERASER, "Eraser", Icons.Default.AutoFixNormal)
)
private val MARKUP_TOOLS = listOf(
    ToolItem(AnnotationViewModel.AnnotationTool.HIGHLIGHT, "Highlight", Icons.Default.HighlightAlt),
    ToolItem(AnnotationViewModel.AnnotationTool.UNDERLINE, "Underline", Icons.Default.FormatUnderlined),
    ToolItem(AnnotationViewModel.AnnotationTool.STRIKEOUT, "Strikeout", Icons.Default.FormatStrikethrough),
    ToolItem(AnnotationViewModel.AnnotationTool.SQUIGGLY, "Squiggly", Icons.Default.Waves)
)
private val TEXT_TOOLS = listOf(
    ToolItem(AnnotationViewModel.AnnotationTool.TEXT, "Text", Icons.Default.TextFields),
    ToolItem(AnnotationViewModel.AnnotationTool.STICKY_NOTE, "Note", Icons.Default.NoteAlt),
    ToolItem(AnnotationViewModel.AnnotationTool.TEXTBOX, "Text Box", Icons.Default.TextSnippet),
    ToolItem(AnnotationViewModel.AnnotationTool.FREE_TEXT, "Free Text", Icons.Default.Title)
)
private val SHAPE_TOOLS = listOf(
    ToolItem(AnnotationViewModel.AnnotationTool.RECTANGLE, "Rectangle", Icons.Default.CropSquare),
    ToolItem(AnnotationViewModel.AnnotationTool.CIRCLE, "Circle", Icons.Default.Circle),
    ToolItem(AnnotationViewModel.AnnotationTool.LINE, "Line", Icons.Default.Remove),
    ToolItem(AnnotationViewModel.AnnotationTool.ARROW, "Arrow", Icons.Default.ArrowForward),
    ToolItem(AnnotationViewModel.AnnotationTool.POLYGON, "Polygon", Icons.Default.Hexagon),
    ToolItem(AnnotationViewModel.AnnotationTool.CLOUD, "Cloud", Icons.Default.Cloud)
)
private val STAMP_TOOLS = listOf(
    ToolItem(AnnotationViewModel.AnnotationTool.STAMP, "Stamp", Icons.Default.Approval),
    ToolItem(AnnotationViewModel.AnnotationTool.DATE_STAMP, "Date", Icons.Default.CalendarToday),
    ToolItem(AnnotationViewModel.AnnotationTool.SIGNATURE, "Signature", Icons.Default.Draw)
)
private val ALL_TOOLS = DRAWING_TOOLS + MARKUP_TOOLS + TEXT_TOOLS + SHAPE_TOOLS + STAMP_TOOLS
private val CATEGORY_BOUNDARIES = setOf(
    DRAWING_TOOLS.size,
    DRAWING_TOOLS.size + MARKUP_TOOLS.size,
    DRAWING_TOOLS.size + MARKUP_TOOLS.size + TEXT_TOOLS.size,
    DRAWING_TOOLS.size + MARKUP_TOOLS.size + TEXT_TOOLS.size + SHAPE_TOOLS.size
)
