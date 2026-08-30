package com.propdf.editor.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfEditorScreen(
    documentUri: String,
    onNavigateBack: () -> Unit,
    onSaveComplete: (String) -> Unit,
    onNavigateToMerge: () -> Unit = {},
    onNavigateToSplit: () -> Unit = {},
    viewModel: PdfEditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedPages by viewModel.selectedPages.collectAsStateWithLifecycle()
    val thumbnails by viewModel.thumbnails.collectAsStateWithLifecycle()
    val operationMessage by viewModel.operationMessage.collectAsStateWithLifecycle()
    val operationInProgress by viewModel.operationInProgress.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(documentUri) {
        viewModel.loadDocument(Uri.parse(documentUri))
    }

    LaunchedEffect(operationMessage) {
        operationMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeOperationMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF Editor") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.saveDocument() }) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = uiState) {
                is EditorUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is EditorUiState.Error -> {
                    ErrorEditorState(
                        message = state.message,
                        onRetry = { viewModel.loadDocument(Uri.parse(documentUri)) }
                    )
                }
                is EditorUiState.Ready -> {
                    EditorContent(
                        pageCount = state.pageCount,
                        thumbnails = thumbnails,
                        selectedPages = selectedPages,
                        operationInProgress = operationInProgress,
                        onTogglePage = { viewModel.togglePageSelection(it) },
                        onSelectAll = { viewModel.selectAllPages() },
                        onClearSelection = { viewModel.clearSelection() },
                        onDeleteSelected = { viewModel.deletePages(selectedPages.toList()) },
                        onDuplicateSelected = { viewModel.duplicatePages(selectedPages.toList()) },
                        onRotateSelected = { viewModel.rotatePages(selectedPages.toList()) },
                        onMergePdf = onNavigateToMerge,
                        onSplitPdf = onNavigateToSplit,
                        onExtractSelected = { viewModel.extractSelectedPages(selectedPages.toList()) },
                        onCompress = { viewModel.compressDocument() },
                        onApplyWatermark = { text -> viewModel.applyWatermark(text) },
                        onApplyPageNumbers = { viewModel.applyPageNumbers() },
                        onApplyCrop = { margin -> viewModel.cropDocument(margin, selectedPages.toList()) }
                    )
                }
                is EditorUiState.Saved -> {
                    LaunchedEffect(Unit) {
                        onSaveComplete(documentUri)
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorContent(
    pageCount: Int,
    thumbnails: Map<Int, Bitmap>,
    selectedPages: Set<Int>,
    operationInProgress: Boolean,
    onTogglePage: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onDuplicateSelected: () -> Unit,
    onRotateSelected: () -> Unit,
    onMergePdf: () -> Unit,
    onSplitPdf: () -> Unit,
    onExtractSelected: () -> Unit,
    onCompress: () -> Unit,
    onApplyWatermark: (String) -> Unit,
    onApplyPageNumbers: () -> Unit,
    onApplyCrop: (Float) -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showWatermarkDialog by remember { mutableStateOf(false) }
    var showCropDialog by remember { mutableStateOf(false) }

    if (showWatermarkDialog) {
        WatermarkDialog(
            onDismiss = { showWatermarkDialog = false },
            onConfirm = { text ->
                showWatermarkDialog = false
                onApplyWatermark(text)
            }
        )
    }

    if (showCropDialog) {
        CropMarginDialog(
            onDismiss = { showCropDialog = false },
            onConfirm = { margin ->
                showCropDialog = false
                onApplyCrop(margin)
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (selectedPages.isEmpty()) "$pageCount pages"
                        else "${selectedPages.size} of $pageCount selected",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row {
                        TextButton(onClick = onSelectAll) { Text("Select all") }
                        TextButton(
                            onClick = onClearSelection,
                            enabled = selectedPages.isNotEmpty()
                        ) { Text("Clear") }
                    }
                }
            }

            item {
                // Real page-management workflow: tap a thumbnail to select/deselect it,
                // then the operation buttons below act on the actual selection. Previously
                // there was no selection model at all -- Delete/Duplicate/Rotate always
                // called into the ViewModel with a hardcoded page (0, or an empty list),
                // so the buttons appeared functional but silently acted on the wrong page
                // or nothing.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.heightIn(max = 480.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pageCount) { pageIndex ->
                        PageThumbnail(
                            pageNumber = pageIndex + 1,
                            bitmap = thumbnails[pageIndex],
                            selected = selectedPages.contains(pageIndex),
                            onClick = { onTogglePage(pageIndex) }
                        )
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Page Operations", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EditorToolCard(
                        icon = Icons.Default.Delete,
                        label = "Delete",
                        enabled = selectedPages.isNotEmpty(),
                        onClick = onDeleteSelected,
                        modifier = Modifier.weight(1f)
                    )
                    EditorToolCard(
                        icon = Icons.Default.ContentCopy,
                        label = "Duplicate",
                        enabled = selectedPages.isNotEmpty(),
                        onClick = onDuplicateSelected,
                        modifier = Modifier.weight(1f)
                    )
                    EditorToolCard(
                        icon = Icons.Default.RotateRight,
                        label = "Rotate",
                        enabled = selectedPages.isNotEmpty(),
                        onClick = onRotateSelected,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EditorToolCard(
                        icon = Icons.Default.MergeType,
                        label = "Merge",
                        onClick = onMergePdf,
                        modifier = Modifier.weight(1f)
                    )
                    EditorToolCard(
                        icon = Icons.Default.CallSplit,
                        label = "Split",
                        onClick = onSplitPdf,
                        modifier = Modifier.weight(1f)
                    )
                    EditorToolCard(
                        icon = Icons.Default.Output,
                        label = "Extract",
                        enabled = selectedPages.isNotEmpty() && !operationInProgress,
                        onClick = onExtractSelected,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Content", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EditorToolCard(
                        icon = Icons.Default.Compress,
                        label = "Compress",
                        enabled = !operationInProgress,
                        onClick = onCompress,
                        modifier = Modifier.weight(1f)
                    )
                    EditorToolCard(
                        icon = Icons.Default.WaterDrop,
                        label = "Watermark",
                        enabled = !operationInProgress,
                        onClick = { showWatermarkDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                    EditorToolCard(
                        icon = Icons.Default.FormatListNumbered,
                        label = "Page Numbers",
                        enabled = !operationInProgress,
                        onClick = onApplyPageNumbers,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EditorToolCard(
                        icon = Icons.Default.Crop,
                        label = "Crop",
                        enabled = !operationInProgress,
                        onClick = { showCropDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (operationInProgress) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun WatermarkDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("CONFIDENTIAL") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add watermark") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Watermark text") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CropMarginDialog(onDismiss: () -> Unit, onConfirm: (Float) -> Unit) {
    var marginText by remember { mutableStateOf("36") }
    val marginPt = marginText.toFloatOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crop pages") },
        text = {
            Column {
                Text(
                    "Applies to the selected pages, or all pages if none are selected.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = marginText,
                    onValueChange = { marginText = it },
                    label = { Text("Margin (points)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { marginPt?.let(onConfirm) },
                enabled = marginPt != null && marginPt >= 0f
            ) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun PageThumbnail(
    pageNumber: Int,
    bitmap: Bitmap?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (selected) {
                        Modifier.border(
                            BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary),
                            RoundedCornerShape(6.dp)
                        )
                    } else Modifier
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Page $pageNumber",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
        Text(
            "$pageNumber",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun EditorToolCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    ElevatedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.aspectRatio(1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        }
    }
}

@Composable
private fun ErrorEditorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}
