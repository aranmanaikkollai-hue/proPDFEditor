package com.propdf.editor.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    viewModel: PdfEditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(documentUri) {
        viewModel.loadDocument(Uri.parse(documentUri))
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
                    EditorToolsGrid(
                        pageCount = state.pageCount,
                        onDeletePages = { viewModel.deletePages(it) },
                        onDuplicatePage = { viewModel.duplicatePage(it) },
                        onRotatePage = { viewModel.rotatePage(it) },
                        onExtractPages = { viewModel.extractPages(it) },
                        onMergePdf = { /* Launch merge flow */ },
                        onSplitPdf = { /* Launch split flow */ },
                        onCompress = { viewModel.compress() },
                        onAddWatermark = { viewModel.addWatermark(it) },
                        onAddPageNumbers = { viewModel.addPageNumbers() }
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
private fun EditorToolsGrid(
    pageCount: Int,
    onDeletePages: (List<Int>) -> Unit,
    onDuplicatePage: (Int) -> Unit,
    onRotatePage: (Int) -> Unit,
    onExtractPages: (List<Int>) -> Unit,
    onMergePdf: () -> Unit,
    onSplitPdf: () -> Unit,
    onCompress: () -> Unit,
    onAddWatermark: (String) -> Unit,
    onAddPageNumbers: () -> Unit
) {
    var showWatermarkDialog by remember { mutableStateOf(false) }
    var watermarkText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Page Operations",
                style = MaterialTheme.typography.titleMedium
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                EditorToolCard(
                    icon = Icons.Default.Delete,
                    label = "Delete",
                    onClick = { onDeletePages(emptyList()) },
                    modifier = Modifier.weight(1f)
                )
                EditorToolCard(
                    icon = Icons.Default.ContentCopy,
                    label = "Duplicate",
                    onClick = { onDuplicatePage(0) },
                    modifier = Modifier.weight(1f)
                )
                EditorToolCard(
                    icon = Icons.Default.RotateRight,
                    label = "Rotate",
                    onClick = { onRotatePage(0) },
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
                    icon = Icons.Default.Compress,
                    label = "Compress",
                    onClick = onCompress,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                "Content",
                style = MaterialTheme.typography.titleMedium
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                EditorToolCard(
                    icon = Icons.Default.WaterDrop,
                    label = "Watermark",
                    onClick = { showWatermarkDialog = true },
                    modifier = Modifier.weight(1f)
                )
                EditorToolCard(
                    icon = Icons.Default.FormatListNumbered,
                    label = "Page Numbers",
                    onClick = onAddPageNumbers,
                    modifier = Modifier.weight(1f)
                )
                EditorToolCard(
                    icon = Icons.Default.Crop,
                    label = "Crop",
                    onClick = { },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                "$pageCount pages",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showWatermarkDialog) {
        AlertDialog(
            onDismissRequest = { showWatermarkDialog = false },
            title = { Text("Add Watermark") },
            text = {
                OutlinedTextField(
                    value = watermarkText,
                    onValueChange = { watermarkText = it },
                    label = { Text("Watermark Text") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWatermarkDialog = false
                        onAddWatermark(watermarkText)
                    }
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWatermarkDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EditorToolCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        onClick = onClick,
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
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge
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
