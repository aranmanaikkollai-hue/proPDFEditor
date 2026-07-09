@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.propdf.editor.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.propdf.annotations.ui.AnnotationToolbar
import com.propdf.annotations.ui.AnnotationViewModel

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    uri: String,
    onBack: () -> Unit,
    annotationViewModel: AnnotationViewModel = hiltViewModel(),
    pdfViewModel: PdfViewerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    val isLoading by pdfViewModel.isLoading.collectAsState()
    val errorMsg by pdfViewModel.errorMessage.collectAsState()
    val fileName by pdfViewModel.fileName.collectAsState()
    val pageCount by pdfViewModel.pageCount.collectAsState()
    val viewMode by pdfViewModel.viewMode.collectAsState()
    val colorMode by pdfViewModel.colorMode.collectAsState()
    val zoom by pdfViewModel.zoom.collectAsState()
    val rotation by pdfViewModel.rotation.collectAsState()
    val currentPage by pdfViewModel.currentPage.collectAsState()
    val isFullscreen by pdfViewModel.isFullscreen.collectAsState()
    val isThumbnailSidebarOpen by pdfViewModel.isThumbnailSidebarOpen.collectAsState()
    val isBookmarksPanelOpen by pdfViewModel.isBookmarksPanelOpen.collectAsState()
    val isSearchActive by pdfViewModel.isSearchActive.collectAsState()
    val searchResults by pdfViewModel.searchResults.collectAsState()
    val currentSearchMatchIndex by pdfViewModel.currentSearchMatchIndex.collectAsState()
    val bookmarks by pdfViewModel.bookmarks.collectAsState()
    val keepScreenOn by pdfViewModel.keepScreenOn.collectAsState()
    val autoBrightness by pdfViewModel.autoBrightness.collectAsState()
    val manualBrightness by pdfViewModel.manualBrightness.collectAsState()

    var showAnnotations by remember { mutableStateOf(false) }
    var showDisplaySettings by remember { mutableStateOf(false) }
    var showPageNavigator by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val pagerState = rememberPagerState(initialPage = 0) { spreadCount(viewMode, pageCount) }

    LaunchedEffect(uri) { pdfViewModel.openDocument(uri) }

    // Prefer two-page spreads by default on wide/landscape tablets, once, without fighting
    // a user's explicit later choice.
    var appliedTabletDefault by remember { mutableStateOf(false) }
    LaunchedEffect(isTablet, isLandscape, pageCount) {
        if (!appliedTabletDefault && pageCount > 0) {
            appliedTabletDefault = true
            if (isTablet && isLandscape && viewMode == PdfViewMode.VERTICAL_CONTINUOUS) {
                pdfViewModel.setViewMode(PdfViewMode.TWO_PAGE)
            }
        }
    }

    // Keep screen on
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Brightness: manual override, or defer back to the system's own auto-brightness
    DisposableEffect(autoBrightness, manualBrightness) {
        val window = context.findActivity()?.window
        if (window != null) {
            val attrs = window.attributes
            attrs.screenBrightness = if (autoBrightness) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                manualBrightness
            }
            window.attributes = attrs
        }
        onDispose {
            val w = context.findActivity()?.window
            if (w != null) {
                val attrs = w.attributes
                attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = attrs
            }
        }
    }

    // Fullscreen / reading mode: hide system bars along with our own top bar
    DisposableEffect(isFullscreen) {
        val window = context.findActivity()?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (isFullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val w = context.findActivity()?.window
            if (w != null) WindowCompat.getInsetsController(w, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // React to explicit "jump to page" events (search, bookmarks, thumbnails, navigator)
    LaunchedEffect(viewMode) {
        pdfViewModel.navigateToPage.collect { target ->
            when (viewMode) {
                PdfViewMode.VERTICAL_CONTINUOUS, PdfViewMode.HORIZONTAL_CONTINUOUS ->
                    listState.animateScrollToItem(target)
                PdfViewMode.SINGLE_PAGE ->
                    pagerState.animateScrollToPage(target)
                PdfViewMode.TWO_PAGE ->
                    pagerState.animateScrollToPage(target / 2)
            }
        }
    }

    // Track visible page for the current-page indicator + preloading, per mode
    LaunchedEffect(viewMode, listState) {
        if (viewMode == PdfViewMode.VERTICAL_CONTINUOUS || viewMode == PdfViewMode.HORIZONTAL_CONTINUOUS) {
            snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
                pdfViewModel.onPageVisible(index)
            }
        }
    }
    LaunchedEffect(viewMode, pagerState) {
        if (viewMode == PdfViewMode.SINGLE_PAGE || viewMode == PdfViewMode.TWO_PAGE) {
            snapshotFlow { pagerState.currentPage }.collect { page ->
                val actualPage = if (viewMode == PdfViewMode.TWO_PAGE) page * 2 else page
                pdfViewModel.onPageVisible(actualPage)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!isFullscreen) {
                if (isSearchActive) {
                    PdfSearchBar(pdfViewModel = pdfViewModel, onClose = { pdfViewModel.setSearchActive(false) })
                } else {
                    TopAppBar(
                        title = { Text(fileName, maxLines = 1) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = { pdfViewModel.setSearchActive(true) }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                            IconButton(onClick = { pdfViewModel.toggleBookmark(currentPage) }) {
                                Icon(
                                    if (currentPage in bookmarks) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = "Bookmark this page",
                                    tint = if (currentPage in bookmarks) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
                            }
                            IconButton(onClick = { pdfViewModel.toggleThumbnailSidebar() }) {
                                Icon(Icons.Default.GridView, contentDescription = "Thumbnails")
                            }
                            IconButton(onClick = { showAnnotations = !showAnnotations }) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Toggle annotations",
                                    tint = if (showAnnotations) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
                            }
                            Box {
                                IconButton(onClick = { showMoreMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                                }
                                DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Go to page") },
                                        leadingIcon = { Icon(Icons.Default.Numbers, null) },
                                        onClick = { showMoreMenu = false; showPageNavigator = true }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Bookmarks") },
                                        leadingIcon = { Icon(Icons.Default.Bookmarks, null) },
                                        onClick = { showMoreMenu = false; pdfViewModel.toggleBookmarksPanel() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Rotate") },
                                        leadingIcon = { Icon(Icons.Default.RotateRight, null) },
                                        onClick = { showMoreMenu = false; pdfViewModel.rotate() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Reading mode") },
                                        leadingIcon = { Icon(Icons.Default.Fullscreen, null) },
                                        onClick = { showMoreMenu = false; pdfViewModel.toggleFullscreen() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Display settings") },
                                        leadingIcon = { Icon(Icons.Default.Tune, null) },
                                        onClick = { showMoreMenu = false; showDisplaySettings = true }
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullscreen) PaddingValues(0.dp) else padding)
        ) {
            if (showAnnotations && !isFullscreen) {
                AnnotationToolbar(viewModel = annotationViewModel, snackbarHostState = snackbarHostState)
            }

            Row(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                val current = pdfViewModel.zoom.value
                                pdfViewModel.setZoom(if (current > 1.05f) 1f else 2.5f)
                            })
                        }
                ) {
                    when {
                        isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        errorMsg != null -> Text(
                            text = errorMsg ?: "",
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        pageCount > 0 -> {
                            val activeMatch = searchResults.getOrNull(currentSearchMatchIndex)
                            when (viewMode) {
                                PdfViewMode.VERTICAL_CONTINUOUS -> ContinuousPager(
                                    orientationVertical = true,
                                    listState = listState,
                                    pageCount = pageCount,
                                    pdfViewModel = pdfViewModel,
                                    annotationViewModel = annotationViewModel,
                                    zoom = zoom,
                                    rotation = rotation,
                                    colorMode = colorMode,
                                    showAnnotations = showAnnotations,
                                    searchResults = searchResults,
                                    activeMatch = activeMatch
                                )
                                PdfViewMode.HORIZONTAL_CONTINUOUS -> ContinuousPager(
                                    orientationVertical = false,
                                    listState = listState,
                                    pageCount = pageCount,
                                    pdfViewModel = pdfViewModel,
                                    annotationViewModel = annotationViewModel,
                                    zoom = zoom,
                                    rotation = rotation,
                                    colorMode = colorMode,
                                    showAnnotations = showAnnotations,
                                    searchResults = searchResults,
                                    activeMatch = activeMatch
                                )
                                PdfViewMode.SINGLE_PAGE -> PagedContent(
                                    pagerState = pagerState,
                                    twoPage = false,
                                    pageCount = pageCount,
                                    pdfViewModel = pdfViewModel,
                                    annotationViewModel = annotationViewModel,
                                    zoom = zoom,
                                    rotation = rotation,
                                    colorMode = colorMode,
                                    showAnnotations = showAnnotations,
                                    searchResults = searchResults,
                                    activeMatch = activeMatch
                                )
                                PdfViewMode.TWO_PAGE -> PagedContent(
                                    pagerState = pagerState,
                                    twoPage = true,
                                    pageCount = pageCount,
                                    pdfViewModel = pdfViewModel,
                                    annotationViewModel = annotationViewModel,
                                    zoom = zoom,
                                    rotation = rotation,
                                    colorMode = colorMode,
                                    showAnnotations = showAnnotations,
                                    searchResults = searchResults,
                                    activeMatch = activeMatch
                                )
                            }

                            FloatingPageIndicator(
                                currentPage = currentPage,
                                pageCount = pageCount,
                                visibleTrigger = currentPage,
                                modifier = Modifier.align(Alignment.BottomCenter)
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isThumbnailSidebarOpen,
                    enter = slideInHorizontally(initialOffsetX = { it }),
                    exit = slideOutHorizontally(targetOffsetX = { it })
                ) {
                    ThumbnailSidebar(pdfViewModel = pdfViewModel, onDismiss = { pdfViewModel.toggleThumbnailSidebar() })
                }
                AnimatedVisibility(
                    visible = isBookmarksPanelOpen,
                    enter = slideInHorizontally(initialOffsetX = { it }),
                    exit = slideOutHorizontally(targetOffsetX = { it })
                ) {
                    BookmarksPanel(pdfViewModel = pdfViewModel, onDismiss = { pdfViewModel.toggleBookmarksPanel() })
                }
            }
        }
    }

    if (showPageNavigator) {
        PageNavigatorDialog(
            pageCount = pageCount,
            currentPage = currentPage,
            onNavigate = { pdfViewModel.jumpToPage(it); showPageNavigator = false },
            onDismiss = { showPageNavigator = false }
        )
    }
    if (showDisplaySettings) {
        DisplaySettingsSheet(pdfViewModel = pdfViewModel, onDismiss = { showDisplaySettings = false })
    }
}

private fun spreadCount(mode: PdfViewMode, pageCount: Int): Int =
    if (mode == PdfViewMode.TWO_PAGE) ((pageCount + 1) / 2).coerceAtLeast(1) else pageCount.coerceAtLeast(1)

@Composable
private fun ContinuousPager(
    orientationVertical: Boolean,
    listState: LazyListState,
    pageCount: Int,
    pdfViewModel: PdfViewerViewModel,
    annotationViewModel: AnnotationViewModel,
    zoom: Float,
    rotation: Int,
    colorMode: PdfColorMode,
    showAnnotations: Boolean,
    searchResults: List<PdfSearchMatch>,
    activeMatch: PdfSearchMatch?
) {
    val context = LocalContext.current
    val targetWidthPx = remember { context.resources.displayMetrics.widthPixels }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { center ->
            pdfViewModel.preloadAround(center, targetWidthPx)
        }
    }

    // IMPORTANT: keyed on Unit, NOT on `zoom`. Re-keying on a value that changes on every
    // gesture callback would cancel and restart detectTransformGestures mid-pinch, aborting
    // the gesture every frame. Reading pdfViewModel.zoom.value (a live StateFlow snapshot)
    // instead of the `zoom` parameter avoids the stale-closure problem of a rapidly repeating
    // gesture callback only ever seeing the value from the last recomposition.
    val pinchModifier = Modifier.pointerInput(Unit) {
        detectTransformGestures { _, _, gestureZoom, _ ->
            pdfViewModel.setZoom(pdfViewModel.zoom.value * gestureZoom)
        }
    }

    val pageModifier = pinchModifier.graphicsLayer(scaleX = zoom, scaleY = zoom, rotationZ = rotation.toFloat())

    if (orientationVertical) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(count = pageCount) { index ->
                Box(pageModifier.fillMaxWidth()) {
                    PdfPageView(
                        pageIndex = index,
                        pdfViewModel = pdfViewModel,
                        annotationViewModel = annotationViewModel,
                        targetWidthPx = targetWidthPx,
                        colorMode = colorMode,
                        showAnnotations = showAnnotations,
                        searchMatches = searchResults.filter { it.pageIndex == index },
                        activeSearchMatch = activeMatch?.takeIf { it.pageIndex == index }
                    )
                }
                if (index < pageCount - 1) Spacer(Modifier.height(4.dp))
            }
        }
    } else {
        LazyRow(state = listState, modifier = Modifier.fillMaxSize()) {
            items(count = pageCount) { index ->
                Box(pageModifier.fillParentMaxWidth().fillMaxHeight()) {
                    PdfPageView(
                        pageIndex = index,
                        pdfViewModel = pdfViewModel,
                        annotationViewModel = annotationViewModel,
                        targetWidthPx = targetWidthPx,
                        colorMode = colorMode,
                        showAnnotations = showAnnotations,
                        searchMatches = searchResults.filter { it.pageIndex == index },
                        activeSearchMatch = activeMatch?.takeIf { it.pageIndex == index }
                    )
                }
            }
        }
    }
}

@Composable
private fun PagedContent(
    pagerState: PagerState,
    twoPage: Boolean,
    pageCount: Int,
    pdfViewModel: PdfViewerViewModel,
    annotationViewModel: AnnotationViewModel,
    zoom: Float,
    rotation: Int,
    colorMode: PdfColorMode,
    showAnnotations: Boolean,
    searchResults: List<PdfSearchMatch>,
    activeMatch: PdfSearchMatch?
) {
    val context = LocalContext.current
    val fullWidthPx = remember { context.resources.displayMetrics.widthPixels }
    val targetWidthPx = if (twoPage) fullWidthPx / 2 else fullWidthPx
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(pagerState.currentPage) { panOffset = Offset.Zero }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val center = if (twoPage) page * 2 else page
            pdfViewModel.preloadAround(center, targetWidthPx, radius = 1)
        }
    }

    HorizontalPager(
        state = pagerState,
        // Disabling the pager's own drag while zoomed hands single-finger gestures to our
        // manual pan handler below instead of changing pages mid-zoom.
        userScrollEnabled = zoom <= 1.05f,
        modifier = Modifier
            .fillMaxSize()
            // Keyed on Unit for the same reason as ContinuousPager: never re-key on the
            // volatile zoom value itself, and always read pdfViewModel.zoom.value live.
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val newZoom = pdfViewModel.zoom.value * gestureZoom
                    pdfViewModel.setZoom(newZoom)
                    if (newZoom > 1.05f) panOffset += pan
                }
            }
    ) { spread ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = zoom,
                    scaleY = zoom,
                    translationX = panOffset.x,
                    translationY = panOffset.y,
                    rotationZ = rotation.toFloat()
                )
        ) {
            if (twoPage) {
                Row(Modifier.fillMaxSize()) {
                    val left = spread * 2
                    val right = left + 1
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        PdfPageView(
                            pageIndex = left,
                            pdfViewModel = pdfViewModel,
                            annotationViewModel = annotationViewModel,
                            targetWidthPx = targetWidthPx,
                            colorMode = colorMode,
                            showAnnotations = showAnnotations,
                            searchMatches = searchResults.filter { it.pageIndex == left },
                            activeSearchMatch = activeMatch?.takeIf { it.pageIndex == left }
                        )
                    }
                    if (right < pageCount) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            PdfPageView(
                                pageIndex = right,
                                pdfViewModel = pdfViewModel,
                                annotationViewModel = annotationViewModel,
                                targetWidthPx = targetWidthPx,
                                colorMode = colorMode,
                                showAnnotations = showAnnotations,
                                searchMatches = searchResults.filter { it.pageIndex == right },
                                activeSearchMatch = activeMatch?.takeIf { it.pageIndex == right }
                            )
                        }
                    }
                }
            } else {
                PdfPageView(
                    pageIndex = spread,
                    pdfViewModel = pdfViewModel,
                    annotationViewModel = annotationViewModel,
                    targetWidthPx = targetWidthPx,
                    colorMode = colorMode,
                    showAnnotations = showAnnotations,
                    searchMatches = searchResults.filter { it.pageIndex == spread },
                    activeSearchMatch = activeMatch?.takeIf { it.pageIndex == spread }
                )
            }
        }
    }
}
