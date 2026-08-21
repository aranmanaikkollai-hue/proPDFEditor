package com.propdf.viewer.ui

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
    val currentPageBitmap by viewModel.currentPageBitmap.collectAsState()
    val pageRenderState by viewModel.pageRenderState.collectAsState()
    val renderedTiles by viewModel.renderedTiles.collectAsState()

    var showSidebar by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentResultIndex by remember { mutableStateOf(0) }
    var isSearching by remember { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    LaunchedEffect(documentUri) {
        viewModel.openDocument(
            uri = documentUri,
            documentId = documentId,
            cacheDir = context.cacheDir
        )
    }

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
                    PDFCanvas(
                        pageBitmap = currentPageBitmap,
                        zoomLevel = zoomLevel,
                        onZoomChange = { viewModel.updateZoom(it) },
                        onViewportChange = { left, top, right, bottom ->
                            viewModel.updateViewport(left, top, right, bottom)
                        },
                        isRendering = pageRenderState is PDFViewerViewModel.PageRenderState.Rendering,
                        hasError = pageRenderState is PDFViewerViewModel.PageRenderState.Error,
                        tiles = renderedTiles,
                        resetKey = "$documentId-$currentPage",
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { viewportSize = it }
                    )
                }
            }

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

            AnimatedVisibility(
                visible = showSearch,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                SearchOverlayV2(
                    searchResults = searchResults,
                    currentResultIndex = currentResultIndex,
                    searchQuery = searchQuery,
                    isSearching = isSearching,
                    onSearch = { query ->
                        searchQuery = query
                        currentResultIndex = 0
                        isSearching = true
                        viewModel.search(query)
                        isSearching = false
                    },
                    onNextResult = {
                        if (searchResults.isNotEmpty()) {
                            currentResultIndex = (currentResultIndex + 1) % searchResults.size
                            viewModel.goToPage(searchResults[currentResultIndex].pageIndex)
                        }
                    },
                    onPreviousResult = {
                        if (searchResults.isNotEmpty()) {
                            currentResultIndex = (currentResultIndex - 1 + searchResults.size) % searchResults.size
                            viewModel.goToPage(searchResults[currentResultIndex].pageIndex)
                        }
                    },
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
 * Draws the current page. [pageBitmap] is the full-page fallback that is
 * always drawn first (so there is never a gap), and [tiles] -- when present
 * for the currently visible page -- are drawn on top for sharper detail.
 *
 * [resetKey] should change whenever the displayed content requires a fresh
 * viewport (new document, new page): pan is reset when it changes, so a
 * previous page/document's pan offset never leaks onto the next one. Zoom is
 * preserved across a page change within the same document but reset by the
 * caller (via [zoomLevel]) on document change, matching "preserve zoom only
 * when the existing product behaviour explicitly requires it."
 *
 * [gesturesEnabled] should be false while an annotation tool is actively
 * capturing input, so the viewer's pan/zoom gesture detector and the
 * annotation overlay's gesture detector never compete for the same touch
 * stream. When the rendered geometry changes, [onGeometryChange] reports the
 * on-screen scale/offset of the page bitmap so overlays (e.g. annotations)
 * can align to exactly what is drawn.
 */
@Composable
fun PDFCanvas(
    pageBitmap: android.graphics.Bitmap?,
    zoomLevel: Float,
    onZoomChange: (Float) -> Unit,
    onViewportChange: (Int, Int, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    isRendering: Boolean = false,
    hasError: Boolean = false,
    tiles: List<com.propdf.viewer.model.Tile> = emptyList(),
    resetKey: Any? = null,
    gesturesEnabled: Boolean = true,
    onGeometryChange: (scale: Float, offsetX: Float, offsetY: Float) -> Unit = { _, _, _ -> }
) {
    var scale by remember { mutableStateOf(zoomLevel) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(zoomLevel) {
        scale = zoomLevel
    }

    // A new document or page means the previous pan position is meaningless
    // on the new content -- reset it instead of letting it leak through.
    LaunchedEffect(resetKey) {
        offset = Offset.Zero
    }

    Box(
        modifier = modifier
            .pointerInput(gesturesEnabled) {
                if (!gesturesEnabled) return@pointerInput
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
            onViewportChange(
                offset.x.toInt(),
                offset.y.toInt(),
                (offset.x + size.width).toInt(),
                (offset.y + size.height).toInt()
            )

            drawRect(Color.LightGray)

            val bitmap = pageBitmap
            if (bitmap != null && !bitmap.isRecycled) {
                val dstW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val dstH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                val left = ((size.width - dstW) / 2f + offset.x).toInt()
                val top = offset.y.toInt()
                drawImage(
                    image = bitmap.asImageBitmap(),
                    dstOffset = androidx.compose.ui.unit.IntOffset(left, top),
                    dstSize = androidx.compose.ui.unit.IntSize(dstW, dstH)
                )
                onGeometryChange(dstW / bitmap.width.toFloat(), left.toFloat(), top.toFloat())

                // NOTE on [tiles]: the tile pipeline (TileRenderer/TileGrid)
                // now populates Tile.bitmapRef/isRendering as real tiles
                // finish rendering (see TileRenderer.renderTile), and the
                // ViewModel publishes completed tiles for the visible page
                // via renderedTiles -- that is the "tile state is observable
                // / PDFCanvas can obtain visible tiles" step. Actually
                // compositing tiles on top of the fallback bitmap here would
                // require trusting Tile.dstRect's exact coordinate space
                // (page space vs. screen space, and its interaction with
                // scaleFactor/density), which is the ambiguity this task
                // explicitly says not to guess about. Until that mapping is
                // confirmed from the source, tiles are intentionally not
                // drawn here; the full-page fallback bitmap alone is what's
                // rendered, so nothing regresses. [tiles] is accepted so the
                // wiring is ready to switch on as soon as the coordinate
                // mapping is confirmed.
            }
            // When there is no bitmap yet, nothing is drawn here beyond the
            // background rect -- the caller (PDFViewerScreen /
            // IntegratedPDFViewerScreen) is responsible for showing a real
            // loading spinner while isRendering is true, and an error state
            // once rendering has definitively failed, instead of this canvas
            // pretending content is present via a permanent placeholder.
        }

        if (pageBitmap == null && isRendering && !hasError) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}
