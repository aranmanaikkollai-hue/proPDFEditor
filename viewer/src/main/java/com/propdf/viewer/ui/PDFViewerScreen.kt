package com.propdf.viewer.ui

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.propdf.viewer.model.SearchResult
import com.propdf.viewer.model.ThumbnailPage
import kotlinx.coroutines.flow.collectLatest

/**
 * Main PDF viewer screen using Jetpack Compose.
 * Implements:
 * - Tile-based rendering canvas
 * - Pinch zoom and pan gestures
 * - Thumbnail sidebar navigation
 * - Search overlay
 * - Loading states and error handling
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PDFViewerScreen(
    documentUri: android.net.Uri,
    documentId: String,
    viewModel: PDFViewerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density

    val viewerState by viewModel.viewerState.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val zoomLevel by viewModel.zoomLevel.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var showSidebar by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    // Open document on first composition
    LaunchedEffect(documentUri) {
        viewModel.openDocument(
            uri = documentUri,
            documentId = documentId,
            cacheDir = context.cacheDir
        )
    }

    // Update viewport when size changes
    LaunchedEffect(viewportSize, currentPage) {
        if (viewportSize.width > 0 && viewportSize.height > 0) {
            viewModel.updateViewport(
                left = 0,
                top = 0,
                right = viewportSize.width,
                bottom = viewportSize.height
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF Viewer") },
                navigationIcon = {
                    IconButton(onClick = { showSidebar = !showSidebar }) {
                        Icon(Icons.Default.Menu, contentDescription = "Navigation")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.updateZoom(zoomLevel * 1.2f) }) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In")
                    }
                    IconButton(onClick = { viewModel.updateZoom(zoomLevel / 1.2f) }) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out")
                    }
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    Text(
                        text = "${currentPage + 1} / $totalPages",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (viewerState) {
                is PDFViewerViewModel.ViewerState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is PDFViewerViewModel.ViewerState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = errorMessage ?: "Unknown error",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                else -> {
                    // Main PDF Canvas
                    PDFCanvas(
                        zoomLevel = zoomLevel,
                        onZoomChange = { viewModel.updateZoom(it) },
                        onViewportChange = { left, top, right, bottom ->
                            viewModel.updateViewport(left, top, right, bottom)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { viewportSize = it }
                    )
                }
            }

            // Thumbnail Sidebar
            AnimatedVisibility(
                visible = showSidebar,
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it }
            ) {
                ThumbnailSidebar(
                    thumbnails = thumbnails,
                    currentPage = currentPage,
                    onPageSelected = { page ->
                        viewModel.goToPage(page)
                        showSidebar = false
                    },
                    modifier = Modifier
                        .width(200.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                )
            }

            // Search Overlay
            AnimatedVisibility(
                visible = showSearch,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                SearchOverlay(
                    searchResults = searchResults,
                    onSearch = { query -> viewModel.search(query) },
                    onResultSelected = { result ->
                        viewModel.goToPage(result.pageIndex)
                    },
                    onDismiss = { showSearch = false }
                )
            }

            if (isLoading && viewerState is PDFViewerViewModel.ViewerState.Ready) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Custom Canvas for tile-based PDF rendering.
 * Handles zoom/pan gestures and delegates tile rendering to ViewModel.
 */
@Composable
private fun PDFCanvas(
    zoomLevel: Float,
    onZoomChange: (Float) -> Unit,
    onViewportChange: (Int, Int, Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(zoomLevel) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Sync external zoom
    LaunchedEffect(zoomLevel) {
        scale = zoomLevel
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(0.25f, 10.0f)
                    val newOffset = offset + pan

                    scale = newScale
                    offset = newOffset
                    onZoomChange(newScale)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Report viewport to ViewModel
            onViewportChange(
                offset.x.toInt(),
                offset.y.toInt(),
                (offset.x + size.width).toInt(),
                (offset.y + size.height).toInt()
            )

            // Draw placeholder background
            drawRect(Color.LightGray)

            // In production, the ViewModel would provide rendered tile bitmaps
            // which we draw here using drawImage()
            // For now, we draw a placeholder grid to show the tile structure
            val tileSize = 512f * scale
            val cols = (size.width / tileSize).toInt() + 2
            val rows = (size.height / tileSize).toInt() + 2

            for (row in 0..rows) {
                for (col in 0..cols) {
                    val x = col * tileSize + offset.x % tileSize
                    val y = row * tileSize + offset.y % tileSize
                    drawRect(
                        color = if ((row + col) % 2 == 0) Color.Gray.copy(alpha = 0.3f)
                                else Color.DarkGray.copy(alpha = 0.3f),
                        topLeft = Offset(x, y),
                        size = Size(tileSize, tileSize)
                    )
                }
            }
        }
    }
}
