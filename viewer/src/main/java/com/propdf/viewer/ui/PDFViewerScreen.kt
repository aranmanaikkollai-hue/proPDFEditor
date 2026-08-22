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
    val pageLayouts by viewModel.pageLayouts.collectAsState()
    val layoutWidth by viewModel.layoutWidth.collectAsState()
    val documentHeight by viewModel.documentHeight.collectAsState()
    val documentScrollY by viewModel.documentScrollY.collectAsState()
    val pageBitmaps by viewModel.pageBitmaps.collectAsState()
    val pageRenderState by viewModel.pageRenderState.collectAsState()

    var showSidebar by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentResultIndex by remember { mutableStateOf(0) }
    var isSearching by remember { mutableStateOf(false) }

    LaunchedEffect(documentUri) {
        viewModel.openDocument(
            uri = documentUri,
            documentId = documentId,
            cacheDir = context.cacheDir
        )
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
                        pageLayouts = pageLayouts,
                        layoutWidth = layoutWidth,
                        documentHeight = documentHeight,
                        pageBitmaps = pageBitmaps,
                        documentScrollY = documentScrollY,
                        currentPageIndex = currentPage,
                        zoomLevel = zoomLevel,
                        onZoomChange = { viewModel.updateZoom(it) },
                        onScrollChange = { scrollY, viewportHeightPts ->
                            viewModel.updateDocumentScroll(scrollY, viewportHeightPts)
                        },
                        isRendering = pageRenderState is PDFViewerViewModel.PageRenderState.Rendering,
                        hasError = pageRenderState is PDFViewerViewModel.PageRenderState.Error,
                        modifier = Modifier.fillMaxSize()
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
 * Renders the PDF as a continuous document viewport -- a vertical column of
 * real page rectangles (from [pageLayouts]) that the user scrolls through,
 * rather than one floating full-page bitmap. [documentScrollY] is the
 * authoritative scroll position (see PDFViewerViewModel.documentScrollY);
 * this composable mirrors it into local state for smooth gesture feedback
 * and reports gesture-driven scroll/zoom changes back via [onScrollChange]/
 * [onZoomChange], but the ViewModel remains the single source of truth --
 * there is no separately-authoritative local viewport model.
 *
 * [pageBitmaps] is the small rendered-page window (current page +/- 1);
 * pages without a bitmap yet still get their correct page-shaped boundary
 * drawn (from [pageLayouts]), so the document never shows a "hole" or lets a
 * page look like it's floating independently of the others.
 *
 * [gesturesEnabled] should be false while an annotation tool is actively
 * capturing input, so the viewer's pan/zoom/scroll gesture detector and the
 * annotation overlay's gesture detector never compete for the same touch
 * stream. [onGeometryChange] reports the on-screen scale/offset of whichever
 * page is [currentPageIndex], so overlays (e.g. annotations) can align to
 * exactly what is drawn for that page.
 */
@Composable
fun PDFCanvas(
    pageLayouts: List<PDFViewerViewModel.PageLayout>,
    layoutWidth: Float,
    documentHeight: Float,
    pageBitmaps: Map<Int, android.graphics.Bitmap>,
    documentScrollY: Float,
    currentPageIndex: Int,
    zoomLevel: Float,
    onZoomChange: (Float) -> Unit,
    onScrollChange: (scrollY: Float, viewportHeightPts: Float) -> Unit,
    modifier: Modifier = Modifier,
    isRendering: Boolean = false,
    hasError: Boolean = false,
    gesturesEnabled: Boolean = true,
    onGeometryChange: (pageIndex: Int, scale: Float, offsetX: Float, offsetY: Float) -> Unit = { _, _, _, _ -> }
) {
    // Local mirrors of the authoritative ViewModel state, kept in sync via
    // LaunchedEffect below and updated locally during gestures for smooth
    // feedback (the same pattern already used for zoom elsewhere in this
    // codebase). The ViewModel is what actually decides scroll clamping and
    // current-page detection -- this composable never invents its own
    // independently-authoritative position.
    var scale by remember { mutableStateOf(zoomLevel) }
    var scrollY by remember { mutableStateOf(documentScrollY) }
    var panX by remember { mutableStateOf(0f) }
    var viewportSizePx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    LaunchedEffect(zoomLevel) { scale = zoomLevel }
    LaunchedEffect(documentScrollY) { scrollY = documentScrollY }

    val baseScale = if (layoutWidth > 0f && viewportSizePx.width > 0) {
        viewportSizePx.width / layoutWidth
    } else 1f

    // Let the ViewModel know the real viewport height (in document units)
    // as soon as it's known, so current-page detection is correct from the
    // very first frame rather than only after the first gesture.
    LaunchedEffect(viewportSizePx, layoutWidth) {
        if (viewportSizePx.height > 0 && baseScale > 0f) {
            val totalScale = (baseScale * scale).coerceAtLeast(0.0001f)
            onScrollChange(scrollY, viewportSizePx.height / totalScale)
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { viewportSizePx = it }
            .pointerInput(gesturesEnabled, layoutWidth) {
                if (!gesturesEnabled) return@pointerInput
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (viewportSizePx.width <= 0 || layoutWidth <= 0f) return@detectTransformGestures
                    val liveBaseScale = viewportSizePx.width / layoutWidth
                    val totalScaleBefore = (liveBaseScale * scale).coerceAtLeast(0.0001f)

                    // Preserve the pinch focal point: the document-space
                    // point currently under the gesture centroid should stay
                    // under the centroid after the zoom is applied.
                    val docYAtCentroid = scrollY + centroid.y / totalScaleBefore

                    val newScale = (scale * zoom).coerceIn(0.25f, 10.0f)
                    val totalScaleAfter = (liveBaseScale * newScale).coerceAtLeast(0.0001f)

                    var newScrollY = docYAtCentroid - centroid.y / totalScaleAfter
                    // The drag component of the gesture scrolls the document
                    // vertically (dragging up reveals content further down).
                    newScrollY -= pan.y / totalScaleAfter
                    val viewportHeightPts = viewportSizePx.height / totalScaleAfter
                    val maxScroll = (documentHeight - viewportHeightPts).coerceAtLeast(0f)
                    newScrollY = newScrollY.coerceIn(0f, maxScroll)

                    val contentWidthPx = layoutWidth * totalScaleAfter
                    val maxPanX = ((contentWidthPx - viewportSizePx.width) / 2f).coerceAtLeast(0f)
                    val newPanX = (panX + pan.x).coerceIn(-maxPanX, maxPanX)

                    scale = newScale
                    scrollY = newScrollY
                    panX = newPanX

                    onZoomChange(newScale)
                    onScrollChange(newScrollY, viewportHeightPts)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Document background, visually distinct from a page's own
            // white background so page boundaries and inter-page gaps read
            // clearly instead of the page looking like it floats over
            // nothing in particular.
            drawRect(Color(0xFFDDDDDD))

            if (layoutWidth <= 0f || pageLayouts.isEmpty()) return@Canvas

            val totalScale = (baseScale * scale).coerceAtLeast(0.0001f)
            val viewportHeightPts = size.height / totalScale
            val screenWidth = layoutWidth * totalScale
            val screenLeft = (size.width - screenWidth) / 2f + panX

            pageLayouts.forEach { layout ->
                val pageBottom = layout.docTop + layout.docHeight
                if (pageBottom < scrollY || layout.docTop > scrollY + viewportHeightPts) {
                    return@forEach
                }

                val screenTop = (layout.docTop - scrollY) * totalScale
                val screenHeight = layout.docHeight * totalScale

                // The page's own document-space rectangle, drawn first so
                // every visible page has a defined, non-overlapping bounds
                // -- a real page in a document, not a floating image --
                // even before its bitmap has finished rendering.
                drawRect(
                    color = Color.White,
                    topLeft = Offset(screenLeft, screenTop),
                    size = Size(screenWidth, screenHeight)
                )

                val bitmap = pageBitmaps[layout.index]
                if (bitmap != null && !bitmap.isRecycled) {
                    drawImage(
                        image = bitmap.asImageBitmap(),
                        dstOffset = androidx.compose.ui.unit.IntOffset(
                            screenLeft.toInt(),
                            screenTop.toInt()
                        ),
                        dstSize = androidx.compose.ui.unit.IntSize(
                            screenWidth.toInt().coerceAtLeast(1),
                            screenHeight.toInt().coerceAtLeast(1)
                        )
                    )
                }

                if (layout.index == currentPageIndex) {
                    onGeometryChange(layout.index, totalScale, screenLeft, screenTop)
                }
            }
        }

        if (pageBitmaps[currentPageIndex] == null && isRendering && !hasError) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}
