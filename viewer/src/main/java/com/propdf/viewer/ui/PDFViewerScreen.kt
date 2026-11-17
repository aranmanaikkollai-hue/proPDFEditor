package com.propdf.viewer.ui

import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.propdf.viewer.model.SearchResult
import com.propdf.viewer.model.ThumbnailPage
import com.propdf.viewer.model.Tile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
                is PDFViewerViewModel.ViewerState.PasswordRequired -> {
                    val state = viewerState as PDFViewerViewModel.ViewerState.PasswordRequired
                    Box(modifier = Modifier.fillMaxSize())
                    PasswordPrompt(
                        previousAttemptFailed = state.previousAttemptFailed,
                        onSubmit = { chars -> viewModel.submitPassword(chars, context.cacheDir) },
                        onCancel = { viewModel.closeDocument() }
                    )
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
 *
 * [layoutMode] selects how pages are arranged (see PageLayoutEngine). VERTICAL and TWO_PAGE scroll vertically
 * ([documentScrollY]) and pan horizontally in pixels; HORIZONTAL scrolls horizontally ([documentScrollX]) and
 * pans vertically in pixels. In HORIZONTAL the content is fitted to the viewport height instead of its width.
 * [onViewportScroll] reports the document x at the left screen edge, the document y at the top, and the visible
 * span in document units; [onScrollChange] is the older vertical-only form and is used only through the default
 * of [onViewportScroll].
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
    onScrollChange: (scrollY: Float, viewportHeightPts: Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    isRendering: Boolean = false,
    hasError: Boolean = false,
    gesturesEnabled: Boolean = true,
    renderedTiles: List<Tile> = emptyList(),
    onViewportChange: (left: Int, top: Int, right: Int, bottom: Int) -> Unit = { _, _, _, _ -> },
    onGeometryChange: (pageIndex: Int, scale: Float, offsetX: Float, offsetY: Float) -> Unit = { _, _, _, _ -> },
    flingCancelToken: Int = 0,
    colorMode: ReaderColorMode = ReaderColorMode.NORMAL,
    layoutMode: PageLayoutMode = PageLayoutMode.VERTICAL,
    documentScrollX: Float = 0f,
    onViewportScroll: (scrollX: Float, scrollY: Float, viewportWidthPts: Float, viewportHeightPts: Float) -> Unit =
        { _, scrollY, _, viewportHeightPts -> onScrollChange(scrollY, viewportHeightPts) }
) {
    val horizontal = layoutMode == PageLayoutMode.HORIZONTAL

    // Reading colour scheme, applied as a draw-time filter only (see ReaderColorMode).
    val colorFilter = remember(colorMode) {
        colorMode.matrix?.let { androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(it)) }
    }

    // Local mirrors of the authoritative ViewModel state, kept in sync via
    // LaunchedEffect below and updated locally during gestures for smooth
    // feedback (the same pattern already used for zoom elsewhere in this
    // codebase). The ViewModel is what actually decides scroll clamping and
    // current-page detection -- this composable never invents its own
    // independently-authoritative position.
    var scale by remember { mutableStateOf(zoomLevel) }
    var scrollY by remember { mutableStateOf(documentScrollY) }
    var scrollX by remember { mutableStateOf(documentScrollX) }
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }
    var viewportSizePx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val flingScope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(zoomLevel) { scale = zoomLevel }
    LaunchedEffect(flingCancelToken) { flingJob?.cancel() }
    LaunchedEffect(documentScrollY) { scrollY = documentScrollY }
    LaunchedEffect(documentScrollX) { scrollX = documentScrollX }
    LaunchedEffect(layoutMode) {
        // A new arrangement starts centred; the ViewModel moves the scroll position to the current page.
        flingJob?.cancel()
        panX = 0f
        panY = 0f
    }

    val baseScale = canvasBaseScale(horizontal, viewportSizePx.width, viewportSizePx.height, layoutWidth, documentHeight)

    // Let the ViewModel know the real viewport size (in document units)
    // as soon as it's known, so current-page detection is correct from the
    // very first frame rather than only after the first gesture.
    LaunchedEffect(viewportSizePx, layoutWidth, documentHeight, layoutMode) {
        if (viewportSizePx.height > 0 && viewportSizePx.width > 0 && baseScale > 0f) {
            val totalScale = (baseScale * scale).coerceAtLeast(0.0001f)
            val originX = canvasOriginX(horizontal, viewportSizePx.width.toFloat(), layoutWidth, totalScale, scrollX, panX)
            onViewportScroll(
                -originX / totalScale,
                scrollY,
                viewportSizePx.width / totalScale,
                viewportSizePx.height / totalScale
            )
        }
    }

    // Feeds the tile-rendering pipeline (TileRenderer/TileGrid, via
    // ViewModel.updateViewport) with which portion of the *current page* is
    // actually on screen, in that page's own pixel space (page width in PDF
    // points x the live zoom x TileGrid's fixed 2x resolution factor -- see
    // TileGrid construction in the ViewModel). The vertical range is always
    // clipped to what is visible. The horizontal range is clipped only in
    // HORIZONTAL layout; in the other layouts the full page width is used
    // (horizontal panning there is limited to keeping the content roughly
    // centered).
    LaunchedEffect(viewportSizePx, scrollY, scrollX, panY, scale, layoutWidth, documentHeight, pageLayouts, currentPageIndex, layoutMode) {
        val layout = pageLayouts.getOrNull(currentPageIndex) ?: return@LaunchedEffect
        if (viewportSizePx.height <= 0 || viewportSizePx.width <= 0 || layoutWidth <= 0f || layout.widthPts <= 0f) {
            return@LaunchedEffect
        }

        val viewW = viewportSizePx.width.toFloat()
        val viewH = viewportSizePx.height.toFloat()
        val liveBaseScale = canvasBaseScale(horizontal, viewportSizePx.width, viewportSizePx.height, layoutWidth, documentHeight)
        val totalScale = (liveBaseScale * scale).coerceAtLeast(0.0001f)
        val originX = canvasOriginX(horizontal, viewW, layoutWidth, totalScale, scrollX, panX)
        val originY = canvasOriginY(horizontal, viewH, documentHeight, totalScale, scrollY, panY)
        val pageScreenLeft = originX + layout.docLeft * totalScale
        val pageScreenWidth = layout.effectiveDocWidth(layoutWidth) * totalScale
        val pageScreenTop = originY + layout.docTop * totalScale
        val pageScreenHeight = layout.docHeight * totalScale
        if (pageScreenHeight <= 0f || pageScreenWidth <= 0f) return@LaunchedEffect

        // TileGrid's fixed resolution multiplier (see TileGrid(i, width,
        // height, 2.0f) in the ViewModel) -- must match here for tile
        // dstRect coordinates to line up with this page's own pixel space.
        val tileGridDensity = 2.0f
        val pagePixelSpaceWidth = layout.widthPts * scale * tileGridDensity
        val pagePixelSpaceHeight = layout.heightPts * scale * tileGridDensity
        if (pagePixelSpaceWidth <= 0f) return@LaunchedEffect

        val visTopScreen = 0f.coerceAtLeast(pageScreenTop)
        val visBottomScreen = viewH.coerceAtMost(pageScreenTop + pageScreenHeight)
        if (visBottomScreen <= visTopScreen) return@LaunchedEffect

        val pageTopPx = ((visTopScreen - pageScreenTop) / pageScreenHeight) * pagePixelSpaceHeight
        val pageBottomPx = ((visBottomScreen - pageScreenTop) / pageScreenHeight) * pagePixelSpaceHeight

        var pageLeftPx = 0f
        var pageRightPx = pagePixelSpaceWidth
        if (horizontal) {
            val visLeftScreen = 0f.coerceAtLeast(pageScreenLeft)
            val visRightScreen = viewW.coerceAtMost(pageScreenLeft + pageScreenWidth)
            if (visRightScreen <= visLeftScreen) return@LaunchedEffect
            pageLeftPx = ((visLeftScreen - pageScreenLeft) / pageScreenWidth) * pagePixelSpaceWidth
            pageRightPx = ((visRightScreen - pageScreenLeft) / pageScreenWidth) * pagePixelSpaceWidth
        }

        onViewportChange(
            pageLeftPx.toInt(),
            pageTopPx.toInt(),
            pageRightPx.toInt().coerceAtLeast(pageLeftPx.toInt() + 1),
            pageBottomPx.toInt().coerceAtLeast(pageTopPx.toInt() + 1)
        )
    }

    Box(
        modifier = modifier
            .onSizeChanged { viewportSizePx = it }
            .pointerInput(gesturesEnabled, layoutWidth, documentHeight, layoutMode) {
                if (!gesturesEnabled) return@pointerInput
                // Observes touches without consuming them (Initial pass) so the
                // pan/zoom detector below keeps working unchanged. Tracks
                // one-finger velocity and, on release, continues the scroll
                // with a decaying fling so long documents can be crossed with
                // a single swipe instead of many slow drags. The fling runs
                // along the scroll axis of the current layout.
                val tracker = VelocityTracker()
                var multiTouchSeen = false
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val changes = event.changes
                        if (changes.any { it.pressed && !it.previousPressed }) {
                            flingJob?.cancel()
                            if (changes.count { it.pressed } == 1) {
                                tracker.resetTracking()
                                multiTouchSeen = false
                            }
                        }
                        if (changes.count { it.pressed } > 1) {
                            multiTouchSeen = true
                            tracker.resetTracking()
                        } else if (changes.size == 1) {
                            val c = changes[0]
                            if (c.pressed) {
                                tracker.addPosition(c.uptimeMillis, c.position)
                            } else if (c.previousPressed && !multiTouchSeen) {
                                val v = tracker.calculateVelocity()
                                val velocity = if (horizontal) v.x else v.y
                                if (kotlin.math.abs(velocity) > 300f && viewportSizePx.width > 0 && viewportSizePx.height > 0 && layoutWidth > 0f) {
                                    val vw = viewportSizePx.width.toFloat()
                                    val vh = viewportSizePx.height.toFloat()
                                    val liveBase = canvasBaseScale(horizontal, viewportSizePx.width, viewportSizePx.height, layoutWidth, documentHeight)
                                    val totalScale = (liveBase * scale).coerceAtLeast(0.0001f)
                                    val viewportWidthPts = vw / totalScale
                                    val viewportHeightPts = vh / totalScale
                                    val maxScroll = if (horizontal) {
                                        (layoutWidth - viewportWidthPts).coerceAtLeast(0f)
                                    } else {
                                        (documentHeight - viewportHeightPts).coerceAtLeast(0f)
                                    }
                                    val start = if (horizontal) scrollX else scrollY
                                    flingJob?.cancel()
                                    flingJob = flingScope.launch {
                                        AnimationState(initialValue = start, initialVelocity = -velocity / totalScale)
                                            .animateDecay(exponentialDecay(frictionMultiplier = 0.6f)) {
                                                val next = value.coerceIn(0f, maxScroll)
                                                if (horizontal) {
                                                    scrollX = next
                                                    onViewportScroll(next, 0f, viewportWidthPts, viewportHeightPts)
                                                } else {
                                                    scrollY = next
                                                    val originX = canvasOriginX(false, vw, layoutWidth, totalScale, scrollX, panX)
                                                    onViewportScroll(-originX / totalScale, next, viewportWidthPts, viewportHeightPts)
                                                }
                                                if (next <= 0f || next >= maxScroll) cancelAnimation()
                                            }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .pointerInput(gesturesEnabled, layoutWidth, documentHeight, layoutMode) {
                if (!gesturesEnabled) return@pointerInput
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (viewportSizePx.width <= 0 || viewportSizePx.height <= 0 || layoutWidth <= 0f) return@detectTransformGestures
                    val vw = viewportSizePx.width.toFloat()
                    val vh = viewportSizePx.height.toFloat()
                    val liveBaseScale = canvasBaseScale(horizontal, viewportSizePx.width, viewportSizePx.height, layoutWidth, documentHeight)
                    val totalScaleBefore = (liveBaseScale * scale).coerceAtLeast(0.0001f)
                    val newScale = (scale * zoom).coerceIn(0.25f, 10.0f)
                    val totalScaleAfter = (liveBaseScale * newScale).coerceAtLeast(0.0001f)
                    val viewportWidthPts = vw / totalScaleAfter
                    val viewportHeightPts = vh / totalScaleAfter

                    if (!horizontal) {
                        // Preserve the pinch focal point: the document-space
                        // point currently under the gesture centroid should stay
                        // under the centroid after the zoom is applied.
                        val docYAtCentroid = scrollY + centroid.y / totalScaleBefore
                        var newScrollY = docYAtCentroid - centroid.y / totalScaleAfter
                        // The drag component of the gesture scrolls the document
                        // vertically (dragging up reveals content further down).
                        newScrollY -= pan.y / totalScaleAfter
                        val maxScroll = (documentHeight - viewportHeightPts).coerceAtLeast(0f)
                        newScrollY = newScrollY.coerceIn(0f, maxScroll)

                        val contentWidthPx = layoutWidth * totalScaleAfter
                        val maxPanX = ((contentWidthPx - vw) / 2f).coerceAtLeast(0f)
                        val newPanX = (panX + pan.x).coerceIn(-maxPanX, maxPanX)

                        scale = newScale
                        scrollY = newScrollY
                        panX = newPanX

                        onZoomChange(newScale)
                        val originX = canvasOriginX(false, vw, layoutWidth, totalScaleAfter, scrollX, newPanX)
                        onViewportScroll(-originX / totalScaleAfter, newScrollY, viewportWidthPts, viewportHeightPts)
                    } else {
                        // Same idea along the horizontal scroll axis: dragging left reveals later pages.
                        val docXAtCentroid = scrollX + centroid.x / totalScaleBefore
                        var newScrollX = docXAtCentroid - centroid.x / totalScaleAfter
                        newScrollX -= pan.x / totalScaleAfter
                        val maxScrollX = (layoutWidth - viewportWidthPts).coerceAtLeast(0f)
                        newScrollX = newScrollX.coerceIn(0f, maxScrollX)

                        val contentHeightPx = documentHeight * totalScaleAfter
                        val maxPanY = ((contentHeightPx - vh) / 2f).coerceAtLeast(0f)
                        val newPanY = (panY + pan.y).coerceIn(-maxPanY, maxPanY)

                        scale = newScale
                        scrollX = newScrollX
                        panY = newPanY

                        onZoomChange(newScale)
                        val originX = canvasOriginX(true, vw, layoutWidth, totalScaleAfter, newScrollX, 0f)
                        onViewportScroll(-originX / totalScaleAfter, 0f, viewportWidthPts, viewportHeightPts)
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Document background, visually distinct from a page's own
            // white background so page boundaries and inter-page gaps read
            // clearly instead of the page looking like it floats over
            // nothing in particular.
            drawRect(Color(colorMode.canvasBackgroundArgb))

            if (layoutWidth <= 0f || pageLayouts.isEmpty()) return@Canvas

            val totalScale = (baseScale * scale).coerceAtLeast(0.0001f)
            val originX = canvasOriginX(horizontal, size.width, layoutWidth, totalScale, scrollX, panX)
            val originY = canvasOriginY(horizontal, size.height, documentHeight, totalScale, scrollY, panY)

            pageLayouts.forEach { layout ->
                val screenLeft = originX + layout.docLeft * totalScale
                val screenTop = originY + layout.docTop * totalScale
                val screenWidth = layout.effectiveDocWidth(layoutWidth) * totalScale
                val screenHeight = layout.docHeight * totalScale

                if (screenTop + screenHeight < 0f || screenTop > size.height ||
                    screenLeft + screenWidth < 0f || screenLeft > size.width
                ) {
                    return@forEach
                }

                // The page's own document-space rectangle, drawn first so
                // every visible page has a defined, non-overlapping bounds
                // -- a real page in a document, not a floating image --
                // even before its bitmap has finished rendering.
                drawRect(
                    color = Color(colorMode.pageBackgroundArgb),
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
                        ),
                        colorFilter = colorFilter
                    )
                }

                // Sharper detail layer: TileRenderer/TileGrid render this
                // page's visible viewport at up to native PDF resolution
                // (independent of the capped fallback bitmap above). Only
                // applied to the current page, matching what
                // scheduleTileRender() actually requests tiles for. Each
                // tile is positioned relative to this page's own on-screen
                // rect, converting from the tile's native page-pixel space
                // (page width in PDF points x the zoom level the grid was
                // built at x its fixed 2x resolution factor) into on-screen
                // pixels. Drawn on top of the fallback bitmap, which stays
                // underneath as the source of truth -- a tile that hasn't
                // finished rendering just leaves the fallback visible
                // instead of a hole or a misplaced patch of blank canvas.
                if (layout.index == currentPageIndex && renderedTiles.isNotEmpty() && layout.widthPts > 0f) {
                    for (tile in renderedTiles) {
                        if (tile.pageIndex != layout.index) continue
                        val tileBitmap = tile.bitmapRef ?: continue
                        if (tileBitmap.isRecycled) continue

                        val tilePixelSpaceWidth = layout.widthPts * tile.zoomLevel * tile.scaleFactor
                        if (tilePixelSpaceWidth <= 0f) continue
                        val tileToScreen = screenWidth / tilePixelSpaceWidth

                        val tLeft = screenLeft + tile.dstRect.left * tileToScreen
                        val tTop = screenTop + tile.dstRect.top * tileToScreen
                        val tRight = screenLeft + tile.dstRect.right * tileToScreen
                        val tBottom = screenTop + tile.dstRect.bottom * tileToScreen
                        val tWidth = (tRight - tLeft).toInt().coerceAtLeast(1)
                        val tHeight = (tBottom - tTop).toInt().coerceAtLeast(1)

                        drawImage(
                            image = tileBitmap.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(tLeft.toInt(), tTop.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(tWidth, tHeight),
                            colorFilter = colorFilter
                        )
                    }
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

/** Page width in document units; layouts built without an explicit width use the shared layout width. */
private fun PDFViewerViewModel.PageLayout.effectiveDocWidth(layoutWidth: Float): Float =
    if (docWidth > 0f) docWidth else layoutWidth

/** Fit-to-width for VERTICAL and TWO_PAGE, fit-to-height for HORIZONTAL. */
private fun canvasBaseScale(horizontal: Boolean, viewW: Int, viewH: Int, contentWidth: Float, contentHeight: Float): Float =
    if (horizontal) {
        if (contentHeight > 0f && viewH > 0) viewH / contentHeight else 1f
    } else {
        if (contentWidth > 0f && viewW > 0) viewW / contentWidth else 1f
    }

/** Screen x of document x = 0. */
private fun canvasOriginX(horizontal: Boolean, viewW: Float, contentW: Float, totalScale: Float, scrollX: Float, panX: Float): Float {
    val contentPx = contentW * totalScale
    return if (horizontal) {
        if (contentPx > viewW) -scrollX * totalScale else (viewW - contentPx) / 2f
    } else {
        (viewW - contentPx) / 2f + panX
    }
}

/** Screen y of document y = 0. */
private fun canvasOriginY(horizontal: Boolean, viewH: Float, contentH: Float, totalScale: Float, scrollY: Float, panY: Float): Float =
    if (horizontal) (viewH - contentH * totalScale) / 2f + panY else -scrollY * totalScale
