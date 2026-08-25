package com.propdfeditor.ui.security

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.propdf.security.ui.view.RedactionOverlayView

/**
 * Interactive redaction: mark rectangles directly on the rendered page, then apply.
 * See RedactionViewModel for how this connects to the real SecurityRepository/
 * RedactionOverlayView backend that already existed but had no screen wired to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedactionScreen(
    documentUri: String,
    onNavigateBack: () -> Unit,
    onRedactionComplete: (String) -> Unit,
    viewModel: RedactionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingRedactions by viewModel.pendingRedactions.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(documentUri) {
        viewModel.loadDocument(documentUri)
    }

    LaunchedEffect(uiState.completedUri) {
        uiState.completedUri?.let { onRedactionComplete(it) }
    }

    uiState.message?.let { msg ->
        LaunchedEffect(msg) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { outputUri: Uri? ->
        outputUri?.let { viewModel.applyRedactions(it) }
    }

    val currentPageMarks = pendingRedactions.filter { it.pageNumber == uiState.currentPage + 1 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (uiState.pageCount > 0) "Redact — Page ${uiState.currentPage + 1} of ${uiState.pageCount}"
                        else "Redact"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { saveLauncher.launch("redacted_document.pdf") },
                        enabled = pendingRedactions.isNotEmpty() && !uiState.isApplying
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Apply redactions")
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
            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.error != null -> {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(uiState.error ?: "Something went wrong")
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val bitmap = uiState.pageBitmap
                        if (bitmap != null && uiState.pageWidthPt > 0f && uiState.pageHeightPt > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .aspectRatio(uiState.pageWidthPt / uiState.pageHeightPt)
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Page ${uiState.currentPage + 1}",
                                    modifier = Modifier.fillMaxSize()
                                )
                                // Tap and drag on this overlay to mark a redaction box; it
                                // draws its own black boxes immediately, and we persist the
                                // real one (converted to PDF-space) through the ViewModel.
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { ctx ->
                                        RedactionOverlayView(ctx).apply {
                                            setOnRedactionAddedListener { screenRect ->
                                                viewModel.addRedactionFromView(screenRect, width.toFloat(), height.toFloat())
                                            }
                                        }
                                    },
                                    update = { view ->
                                        view.clearRedactions()
                                        currentPageMarks.forEach { entity ->
                                            view.addRedaction(
                                                viewModel.pdfRectToViewRect(entity.rect, view.width.toFloat(), view.height.toFloat())
                                            )
                                        }
                                    }
                                )
                            }
                        } else if (uiState.isRenderingPage) {
                            CircularProgressIndicator()
                        }
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { viewModel.previousPage() },
                            enabled = uiState.currentPage > 0 && !uiState.isRenderingPage
                        ) { Text("Previous") }

                        Text(
                            "${pendingRedactions.size} marked",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        TextButton(
                            onClick = { viewModel.nextPage() },
                            enabled = uiState.currentPage < uiState.pageCount - 1 && !uiState.isRenderingPage
                        ) { Text("Next") }
                    }
                }
            }

            if (uiState.isApplying) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
