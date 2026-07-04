package com.propdf.editor.ui.tools.page

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.propdf.core.domain.model.*
import com.propdf.editor.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditorScreen(
    pdfUri: Uri,
    onNavigateBack: () -> Unit,
    onOpenPdf: (Uri) -> Unit,
    viewModel: PageEditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRotateDialog by remember { mutableStateOf(false) }
    var showExtractDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showCropDialog by remember { mutableStateOf(false) }
    var showResizeDialog by remember { mutableStateOf(false) }
    var showInsertBlankDialog by remember { mutableStateOf(false) }
    var showInsertImagePicker by remember { mutableStateOf(false) }
    var showInsertPdfPicker by remember { mutableStateOf(false) }
    var showWatermarkDialog by remember { mutableStateOf(false) }
    var showPageNumberDialog by remember { mutableStateOf(false) }
    var showHeaderFooterDialog by remember { mutableStateOf(false) }
    var showBackgroundDialog by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val config = ImageInsertionConfig(imageUri = it)
            viewModel.insertImagePage(uiState.pageCount + 1, config)
        }
    }

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.insertPdfPages(uiState.pageCount + 1, it)
        }
    }

    LaunchedEffect(pdfUri) {
        viewModel.loadPdf(pdfUri)
    }

    LaunchedEffect(Unit) {
        viewModel.operationResult.collect { result ->
            when (result) {
                is OperationResult.Success -> {
                    snackbarHostState.showSnackbar("Operation completed successfully")
                    onOpenPdf(result.uri)
                }
                is OperationResult.Error -> {
                    snackbarHostState.showSnackbar("Error: ${result.message}")
                }
                else -> {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Page Editor (${uiState.pageCount} pages)") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.pages.any { it.isSelected }) {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                        IconButton(onClick = { showRotateDialog = true }) {
                            Icon(Icons.Default.RotateRight, contentDescription = "Rotate selected")
                        }
                        IconButton(onClick = { showExtractDialog = true }) {
                            Icon(Icons.Default.FileCopy, contentDescription = "Extract selected")
                        }
                        IconButton(onClick = { showMoveDialog = true }) {
                            Icon(Icons.Default.DriveFileMove, contentDescription = "Move selected")
                        }
                    } else {
                        IconButton(onClick = { viewModel.selectAllPages() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                        }
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IconButton(onClick = { showInsertBlankDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Insert blank")
                    }
                    IconButton(onClick = { showInsertImagePicker = true }) {
                        Icon(Icons.Default.Image, contentDescription = "Insert image")
                    }
                    IconButton(onClick = { showInsertPdfPicker = true }) {
                        Icon(Icons.Default.Description, contentDescription = "Insert PDF")
                    }
                    IconButton(onClick = { showCropDialog = true }) {
                        Icon(Icons.Default.Crop, contentDescription = "Crop")
                    }
                    IconButton(onClick = { showResizeDialog = true }) {
                        Icon(Icons.Default.AspectRatio, contentDescription = "Resize")
                    }
                    IconButton(onClick = { showWatermarkDialog = true }) {
                        Icon(Icons.Default.WaterDrop, contentDescription = "Watermark")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showSplitDialog = true },
                icon = { Icon(Icons.Default.CallSplit, null) },
                text = { Text("Split / Merge") }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (uiState.error != null) {
                ErrorState(
                    message = uiState.error!!,
                    onRetry = { viewModel.loadPdf(pdfUri) }
                )
            } else {
                PageGrid(
                    pages = uiState.pages,
                    onPageClick = { pageNumber ->
                        viewModel.togglePageSelection(pageNumber)
                    },
                    onPageLongClick = { pageNumber ->
                        // Show context menu
                    },
                    onDragReorder = { from, to ->
                        viewModel.movePage(from, to)
                    }
                )
            }

            if (uiState.operationInProgress) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
        }
    }

    // Dialogs
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Pages") },
            text = { Text("Delete ${uiState.pages.count { it.isSelected }} selected pages? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelectedPages()
                    showDeleteConfirm = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showRotateDialog) {
        RotateDialog(
            onDismiss = { showRotateDialog = false },
            onRotate = { degrees ->
                viewModel.rotateSelectedPages(degrees)
                showRotateDialog = false
            }
        )
    }

    if (showExtractDialog) {
        ExtractDialog(
            onDismiss = { showExtractDialog = false },
            onExtract = { name ->
                viewModel.extractSelectedPages(name)
                showExtractDialog = false
            }
        )
    }

    if (showMoveDialog) {
        MoveDialog(
            pageCount = uiState.pageCount,
            onDismiss = { showMoveDialog = false },
            onMove = { position ->
                viewModel.moveSelectedPages(position)
                showMoveDialog = false
            }
        )
    }

    if (showCropDialog) {
        CropDialog(
            onDismiss = { showCropDialog = false },
            onCrop = { config ->
                viewModel.cropPages(config)
                showCropDialog = false
            }
        )
    }

    if (showResizeDialog) {
        ResizeDialog(
            onDismiss = { showResizeDialog = false },
            onResize = { config ->
                viewModel.resizePages(config)
                showResizeDialog = false
            }
        )
    }

    if (showInsertBlankDialog) {
        InsertBlankDialog(
            pageCount = uiState.pageCount,
            onDismiss = { showInsertBlankDialog = false },
            onInsert = { position, width, height ->
                viewModel.insertBlankPage(position, width, height)
                showInsertBlankDialog = false
            }
        )
    }

    if (showInsertImagePicker) {
        imagePicker.launch("image/*")
        showInsertImagePicker = false
    }

    if (showInsertPdfPicker) {
        pdfPicker.launch(arrayOf("application/pdf"))
        showInsertPdfPicker = false
    }

    if (showWatermarkDialog) {
        WatermarkDialog(
            onDismiss = { showWatermarkDialog = false },
            onApply = { config ->
                viewModel.addWatermark(config)
                showWatermarkDialog = false
            }
        )
    }

    if (showPageNumberDialog) {
        PageNumberDialog(
            pageCount = uiState.pageCount,
            onDismiss = { showPageNumberDialog = false },
            onApply = { config ->
                viewModel.addPageNumbers(config)
                showPageNumberDialog = false
            }
        )
    }

    if (showHeaderFooterDialog) {
        HeaderFooterDialog(
            onDismiss = { showHeaderFooterDialog = false },
            onApply = { config ->
                viewModel.addHeaderFooter(config)
                showHeaderFooterDialog = false
            }
        )
    }

    if (showBackgroundDialog) {
        BackgroundDialog(
            onDismiss = { showBackgroundDialog = false },
            onApply = { config ->
                viewModel.addBackground(config)
                showBackgroundDialog = false
            }
        )
    }

    if (showCompressDialog) {
        CompressDialog(
            onDismiss = { showCompressDialog = false },
            onApply = { config ->
                viewModel.compressPdf(config)
                showCompressDialog = false
            }
        )
    }

    if (showSplitDialog) {
        SplitMergeDialog(
            onDismiss = { showSplitDialog = false },
            onSplitBySize = { maxSize, prefix ->
                viewModel.splitBySize(maxSize, prefix)
                showSplitDialog = false
            },
            onSplitByBookmark = { prefix ->
                viewModel.splitByBookmark(prefix)
                showSplitDialog = false
            },
            onSplitEveryN = { n, prefix ->
                viewModel.splitEveryNPages(n, prefix)
                showSplitDialog = false
            },
            onOptimize = { aggressive ->
                viewModel.optimizePdf(aggressive)
                showSplitDialog = false
            }
        )
    }
}

@Composable
fun PageGrid(
    pages: List<PageItem>,
    onPageClick: (Int) -> Unit,
    onPageLongClick: (Int) -> Unit,
    onDragReorder: (Int, Int) -> Unit
) {
    val gridState = rememberLazyGridState()
    var draggedItem by remember { mutableStateOf<Int?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(pages, key = { _, page -> page.pageNumber }) { index, page ->
            PageThumbnail(
                page = page,
                isDragging = draggedItem == index,
                onClick = { onPageClick(page.pageNumber) },
                onLongClick = { onPageLongClick(page.pageNumber) },
                modifier = Modifier.pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { draggedItem = index },
                        onDragEnd = {
                            draggedItem?.let { from ->
                                // Calculate drop position
                                val layoutInfo = gridState.layoutInfo
                                val dropIndex = layoutInfo.visibleItemsInfo
                                    .minByOrNull { it.offset.y }?.index ?: from
                                if (from != dropIndex) {
                                    onDragReorder(from, dropIndex)
                                }
                            }
                            draggedItem = null
                        },
                        onDragCancel = { draggedItem = null }
                    ) { _, _ -> }
                }
            )
        }
    }
}

@Composable
fun PageThumbnail(
    page: PageItem,
    isDragging: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = when {
        isDragging -> MaterialTheme.colorScheme.primary
        page.isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val borderWidth = if (page.isSelected || isDragging) 3.dp else 1.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .clip(RoundedCornerShape(12.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            page.thumbnail?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Page ${page.pageNumber}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } ?: Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = page.pageNumber.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (page.isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                )
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                )
            }
        }

        Text(
            text = "Page ${page.pageNumber}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

// ==================== DIALOGS ====================

@Composable
fun RotateDialog(onDismiss: () -> Unit, onRotate: (Int) -> Unit) {
    var degrees by remember { mutableStateOf("90") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rotate Pages") },
        text = {
            Column {
                Text("Enter rotation degrees (clockwise):")
                OutlinedTextField(
                    value = degrees,
                    onValueChange = { degrees = it },
                    label = { Text("Degrees") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { degrees.toIntOrNull()?.let { onRotate(it) } }) {
                Text("Rotate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ExtractDialog(onDismiss: () -> Unit, onExtract: (String) -> Unit) {
    var name by remember { mutableStateOf("extracted") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extract Pages") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Output filename") }
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onExtract(name) }) {
                Text("Extract")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun MoveDialog(pageCount: Int, onDismiss: () -> Unit, onMove: (Int) -> Unit) {
    var position by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move Pages To") },
        text = {
            Column {
                Text("Enter target position (1-$pageCount):")
                OutlinedTextField(
                    value = position,
                    onValueChange = { position = it },
                    label = { Text("Position") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { position.toIntOrNull()?.let { onMove(it) } }) {
                Text("Move")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun CropDialog(onDismiss: () -> Unit, onCrop: (CropConfig) -> Unit) {
    var left by remember { mutableStateOf("0") }
    var right by remember { mutableStateOf("0") }
    var top by remember { mutableStateOf("0") }
    var bottom by remember { mutableStateOf("0") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crop Pages") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter margins in points (1 point = 1/72 inch):")
                OutlinedTextField(value = left, onValueChange = { left = it }, label = { Text("Left") })
                OutlinedTextField(value = right, onValueChange = { right = it }, label = { Text("Right") })
                OutlinedTextField(value = top, onValueChange = { top = it }, label = { Text("Top") })
                OutlinedTextField(value = bottom, onValueChange = { bottom = it }, label = { Text("Bottom") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val config = CropConfig(
                    leftMargin = left.toFloatOrNull() ?: 0f,
                    rightMargin = right.toFloatOrNull() ?: 0f,
                    topMargin = top.toFloatOrNull() ?: 0f,
                    bottomMargin = bottom.toFloatOrNull() ?: 0f
                )
                onCrop(config)
            }) { Text("Crop") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ResizeDialog(onDismiss: () -> Unit, onResize: (ResizeConfig) -> Unit) {
    var width by remember { mutableStateOf("595") }
    var height by remember { mutableStateOf("842") }
    var scaleContent by remember { mutableStateOf(true) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resize Pages") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = width, onValueChange = { width = it }, label = { Text("Width (points)") })
                OutlinedTextField(value = height, onValueChange = { height = it }, label = { Text("Height (points)") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = scaleContent, onCheckedChange = { scaleContent = it })
                    Text("Scale content")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val config = ResizeConfig(
                    targetWidth = width.toFloatOrNull() ?: 595f,
                    targetHeight = height.toFloatOrNull() ?: 842f,
                    scaleContent = scaleContent
                )
                onResize(config)
            }) { Text("Resize") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun InsertBlankDialog(pageCount: Int, onDismiss: () -> Unit, onInsert: (Int, Float, Float) -> Unit) {
    var position by remember { mutableStateOf((pageCount + 1).toString()) }
    var width by remember { mutableStateOf("595") }
    var height by remember { mutableStateOf("842") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert Blank Page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = position, onValueChange = { position = it }, label = { Text("Position") })
                OutlinedTextField(value = width, onValueChange = { width = it }, label = { Text("Width (points)") })
                OutlinedTextField(value = height, onValueChange = { height = it }, label = { Text("Height (points)") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onInsert(
                    position.toIntOrNull() ?: (pageCount + 1),
                    width.toFloatOrNull() ?: 595f,
                    height.toFloatOrNull() ?: 842f
                )
            }) { Text("Insert") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun WatermarkDialog(onDismiss: () -> Unit, onApply: (WatermarkConfig) -> Unit) {
    var text by remember { mutableStateOf("") }
    var opacity by remember { mutableStateOf("0.3") }
    var rotation by remember { mutableStateOf("45") }
    var fontSize by remember { mutableStateOf("48") }
    var position by remember { mutableStateOf(WatermarkPosition.CENTER) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Watermark") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Watermark text") })
                OutlinedTextField(value = opacity, onValueChange = { opacity = it }, label = { Text("Opacity (0-1)") })
                OutlinedTextField(value = rotation, onValueChange = { rotation = it }, label = { Text("Rotation (degrees)") })
                OutlinedTextField(value = fontSize, onValueChange = { fontSize = it }, label = { Text("Font size") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val config = WatermarkConfig(
                    text = text,
                    opacity = opacity.toFloatOrNull() ?: 0.3f,
                    rotation = rotation.toFloatOrNull() ?: 45f,
                    fontSize = fontSize.toFloatOrNull() ?: 48f,
                    position = position
                )
                onApply(config)
            }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun PageNumberDialog(pageCount: Int, onDismiss: () -> Unit, onApply: (PageNumberConfig) -> Unit) {
    var startNumber by remember { mutableStateOf("1") }
    var format by remember { mutableStateOf("{n}") }
    var position by remember { mutableStateOf(PageNumberPosition.BOTTOM_CENTER) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Page Numbers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = startNumber, onValueChange = { startNumber = it }, label = { Text("Start number") })
                OutlinedTextField(value = format, onValueChange = { format = it }, label = { Text("Format ({n}=number, {total}=total)") })
                Text("Position:")
                Column {
                    PageNumberPosition.entries.forEach { pos ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { position = pos }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = position == pos, onClick = { position = pos })
                            Text(pos.name.replace("_", " "), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val config = PageNumberConfig(
                    startNumber = startNumber.toIntOrNull() ?: 1,
                    format = format,
                    position = position
                )
                onApply(config)
            }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun HeaderFooterDialog(onDismiss: () -> Unit, onApply: (HeaderFooterConfig) -> Unit) {
    var header by remember { mutableStateOf("") }
    var footer by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Header & Footer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = header, onValueChange = { header = it }, label = { Text("Header text") })
                OutlinedTextField(value = footer, onValueChange = { footer = it }, label = { Text("Footer text") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val config = HeaderFooterConfig(headerText = header, footerText = footer)
                onApply(config)
            }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun BackgroundDialog(onDismiss: () -> Unit, onApply: (BackgroundConfig) -> Unit) {
    var color by remember { mutableStateOf("FFFFFFFF") }
    var opacity by remember { mutableStateOf("1.0") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Background") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = color, onValueChange = { color = it }, label = { Text("Color (hex ARGB)") })
                OutlinedTextField(value = opacity, onValueChange = { opacity = it }, label = { Text("Opacity") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val config = BackgroundConfig(
                    color = color.toLongOrNull(16)?.toInt() ?: 0xFFFFFFFF.toInt(),
                    opacity = opacity.toFloatOrNull() ?: 1.0f
                )
                onApply(config)
            }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun CompressDialog(onDismiss: () -> Unit, onApply: (CompressConfig) -> Unit) {
    var quality by remember { mutableStateOf("80") }
    var removeMetadata by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compress PDF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = quality, onValueChange = { quality = it }, label = { Text("Image quality (1-100)") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = removeMetadata, onCheckedChange = { removeMetadata = it })
                    Text("Remove metadata")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val config = CompressConfig(
                    imageQuality = quality.toIntOrNull()?.coerceIn(1, 100) ?: 80,
                    removeMetadata = removeMetadata
                )
                onApply(config)
            }) { Text("Compress") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SplitMergeDialog(
    onDismiss: () -> Unit,
    onSplitBySize: (Int, String) -> Unit,
    onSplitByBookmark: (String) -> Unit,
    onSplitEveryN: (Int, String) -> Unit,
    onOptimize: (Boolean) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    var maxSize by remember { mutableStateOf("10") }
    var nPages by remember { mutableStateOf("1") }
    var prefix by remember { mutableStateOf("split") }
    var aggressive by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split, Merge & Optimize") },
        text = {
            Column {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("By Size") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("By Bookmark") })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Every N") })
                    Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("Optimize") })
                }
                Spacer(modifier = Modifier.height(16.dp))
                when (selectedTab) {
                    0 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = maxSize, onValueChange = { maxSize = it }, label = { Text("Max size (MB)") })
                            OutlinedTextField(value = prefix, onValueChange = { prefix = it }, label = { Text("Output prefix") })
                            Button(onClick = { onSplitBySize(maxSize.toIntOrNull() ?: 10, prefix) }) {
                                Text("Split by Size")
                            }
                        }
                    }
                    1 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = prefix, onValueChange = { prefix = it }, label = { Text("Output prefix") })
                            Button(onClick = { onSplitByBookmark(prefix) }) {
                                Text("Split by Bookmark")
                            }
                        }
                    }
                    2 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = nPages, onValueChange = { nPages = it }, label = { Text("Pages per file") })
                            OutlinedTextField(value = prefix, onValueChange = { prefix = it }, label = { Text("Output prefix") })
                            Button(onClick = { onSplitEveryN(nPages.toIntOrNull() ?: 1, prefix) }) {
                                Text("Split Every N Pages")
                            }
                        }
                    }
                    3 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = aggressive, onCheckedChange = { aggressive = it })
                                Text("Aggressive optimization (flatten forms, remove JS)")
                            }
                            Button(onClick = { onOptimize(aggressive) }) {
                                Text("Optimize PDF")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
