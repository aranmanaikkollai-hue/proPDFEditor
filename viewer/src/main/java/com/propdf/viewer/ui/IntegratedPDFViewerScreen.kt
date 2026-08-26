package com.propdf.viewer.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.propdf.annotations.ui.AnnotationOverlay
import com.propdf.annotations.ui.AnnotationToolbar
import com.propdf.annotations.ui.AnnotationViewModel
import kotlinx.coroutines.launch

/**
 * Production-ready PDF viewer screen with full navigation integration,
 * annotation mode toggle, and deep link support.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegratedPDFViewerScreen(
    documentUri: String,
    initialPage: Int = 0,
    onNavigateBack: () -> Unit,
    onNavigateToEditor: (String) -> Unit,
    onNavigateToAnnotations: (String) -> Unit,
    onNavigateToShare: (String) -> Unit,
    onNavigateToSecurity: (String) -> Unit,
    startInAnnotationMode: Boolean = false
) {
    val context = LocalContext.current

    // Mutable so "Choose PDF Again" (after an access failure) can swap in a
    // replacement URI in place, without leaving this screen or standing up a
    // second file-picker/navigation path.
    var activeUriString by remember(documentUri) { mutableStateOf(documentUri) }
    val uri = remember(activeUriString) { Uri.parse(activeUriString) }
    val documentId = remember(activeUriString) { activeUriString.hashCode().toString() }

    val viewerViewModel: PDFViewerViewModel = hiltViewModel()
    val annotationViewModel: AnnotationViewModel = hiltViewModel()

    val viewerState by viewerViewModel.viewerState.collectAsState()
    val currentPage by viewerViewModel.currentPage.collectAsState()
    val totalPages by viewerViewModel.totalPages.collectAsState()
    val zoomLevel by viewerViewModel.zoomLevel.collectAsState()
    val isLoading by viewerViewModel.isLoading.collectAsState()
    val thumbnails by viewerViewModel.thumbnails.collectAsState()
    val errorMessage by viewerViewModel.errorMessage.collectAsState()
    val currentPageSize by viewerViewModel.currentPageSize.collectAsState()
    val pageRenderState by viewerViewModel.pageRenderState.collectAsState()
    val pageLayouts by viewerViewModel.pageLayouts.collectAsState()
    val layoutWidth by viewerViewModel.layoutWidth.collectAsState()
    val documentHeight by viewerViewModel.documentHeight.collectAsState()
    val documentScrollY by viewerViewModel.documentScrollY.collectAsState()
    val pageBitmaps by viewerViewModel.pageBitmaps.collectAsState()
    val renderedTiles by viewerViewModel.renderedTiles.collectAsState()

    var showSidebar by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var annotationMode by remember { mutableStateOf(startInAnnotationMode) }
    var showBottomSheet by remember { mutableStateOf(false) }

    // The actual on-screen scale/offset of the rendered page bitmap, as
    // reported by PDFCanvas. Used instead of a hardcoded scale/Offset.Zero
    // so annotation coordinates line up with what is actually drawn,
    // including centering and pan.
    var renderedScale by remember { mutableStateOf(1f) }
    var renderedOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    // Tracks whether activeUriString currently holds a user-picked
    // replacement (vs. the URI this screen was originally navigated with),
    // so the initialization effect knows whether to also register/update the
    // recent-files entry for it.
    var isReplacementUri by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Reused SAF picker for the "Choose PDF Again" recovery action -- no
    // second file-picker implementation, same picker pattern used elsewhere
    // in the app (see HomeDashboardScreen).
    val replacementPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { pickedUri: Uri? ->
        pickedUri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Some providers don't support persistable grants; the
                // transient grant from the picker result may still be
                // enough for this session.
            }
            isReplacementUri = true
            activeUriString = it.toString()
        }
    }

    // Initialize viewer
    LaunchedEffect(uri) {
        if (isReplacementUri) {
            viewerViewModel.reopenWithReplacementUri(
                uri = uri,
                documentId = documentId,
                cacheDir = context.cacheDir
            )
        } else {
            viewerViewModel.openDocument(
                uri = uri,
                documentId = documentId,
                cacheDir = context.cacheDir,
                initialPage = initialPage
            )
        }
    }

    // Initialize annotations when in annotation mode
    LaunchedEffect(annotationMode, viewerState) {
        if (annotationMode && viewerState is PDFViewerViewModel.ViewerState.Ready) {
            annotationViewModel.initializeDocument(
                documentId = documentId,
                documentPath = uri.path ?: ""
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PDF Viewer", style = MaterialTheme.typography.titleMedium)
                        if (totalPages > 0) {
                            Text(
                                "${currentPage + 1} / $totalPages",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (annotationMode) {
                        IconButton(onClick = { annotationMode = false }) {
                            Icon(Icons.Default.Visibility, contentDescription = "View Mode")
                        }
                    } else {
                        IconButton(onClick = { annotationMode = true }) {
                            Icon(Icons.AutoMirrored.Filled.Comment, contentDescription = "Annotate")
                        }
                    }
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { showBottomSheet = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (annotationMode) {
                AnnotationToolbar(
                    viewModel = annotationViewModel,
                    snackbarHostState = snackbarHostState
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                    val state = viewerState as PDFViewerViewModel.ViewerState.Error
                    ErrorState(
                        message = errorMessage ?: "Failed to load PDF",
                        showChooseAgain = state.isAccessError,
                        onRetry = {
                            viewerViewModel.openDocument(uri, documentId, context.cacheDir)
                        },
                        onChooseAgain = {
                            replacementPickerLauncher.launch(arrayOf("application/pdf"))
                        }
                    )
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
                        onZoomChange = { viewerViewModel.updateZoom(it) },
                        onScrollChange = { scrollY, viewportHeightPts ->
                            viewerViewModel.updateDocumentScroll(scrollY, viewportHeightPts)
                        },
                        isRendering = pageRenderState is PDFViewerViewModel.PageRenderState.Rendering,
                        hasError = pageRenderState is PDFViewerViewModel.PageRenderState.Error,
                        gesturesEnabled = !annotationMode,
                        renderedTiles = renderedTiles,
                        onViewportChange = { left, top, right, bottom ->
                            viewerViewModel.updateViewport(left, top, right, bottom)
                        },
                        onGeometryChange = { _, s, ox, oy ->
                            renderedScale = s
                            renderedOffset = androidx.compose.ui.geometry.Offset(ox, oy)
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (annotationMode) {
                        AnnotationOverlay(
                            viewModel = annotationViewModel,
                            pageIndex = currentPage,
                            pageWidth = currentPageSize.width,
                            pageHeight = currentPageSize.height,
                            pageScale = renderedScale,
                            pageOffset = renderedOffset,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Thumbnail sidebar
            AnimatedVisibility(
                visible = showSidebar,
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it },
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                ThumbnailSidebar(
                    thumbnails = thumbnails,
                    currentPage = currentPage,
                    onPageSelected = { page ->
                        viewerViewModel.goToPage(page)
                        showSidebar = false
                    },
                    modifier = Modifier
                        .width(200.dp)
                        .fillMaxHeight()
                )
            }

            // Search overlay
            AnimatedVisibility(
                visible = showSearch,
                enter = slideInVertically { -it },
                exit = slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                SearchOverlayV2(
                    searchResults = emptyList(),
                    currentResultIndex = 0,
                    searchQuery = "",
                    isSearching = false,
                    onSearch = { query -> viewerViewModel.search(query) },
                    onNextResult = {},
                    onPreviousResult = {},
                    onResultSelected = { result -> viewerViewModel.goToPage(result.pageIndex) },
                    onDismiss = { showSearch = false }
                )
            }

            if (isLoading && viewerState is PDFViewerViewModel.ViewerState.Ready) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    // Tools bottom sheet
    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                ListItem(
                    headlineContent = { Text("Edit PDF") },
                    leadingContent = { Icon(Icons.Default.Edit, null) },
                    modifier = Modifier.clickable {
                        showBottomSheet = false
                        onNavigateToEditor(documentUri)
                    }
                )
                ListItem(
                    headlineContent = { Text("Annotations") },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Comment, null) },
                    modifier = Modifier.clickable {
                        showBottomSheet = false
                        annotationMode = true
                    }
                )
                ListItem(
                    headlineContent = { Text("Security") },
                    leadingContent = { Icon(Icons.Default.Security, null) },
                    modifier = Modifier.clickable {
                        showBottomSheet = false
                        onNavigateToSecurity(documentUri)
                    }
                )
                ListItem(
                    headlineContent = { Text("Share") },
                    leadingContent = { Icon(Icons.Default.Share, null) },
                    modifier = Modifier.clickable {
                        showBottomSheet = false
                        onNavigateToShare(documentUri)
                    }
                )
                ListItem(
                    headlineContent = { Text("Thumbnails") },
                    leadingContent = { Icon(Icons.Default.GridView, null) },
                    modifier = Modifier.clickable {
                        showBottomSheet = false
                        showSidebar = true
                    }
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    showChooseAgain: Boolean,
    onRetry: () -> Unit,
    onChooseAgain: () -> Unit
) {
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
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) {
                Text("Retry")
            }
            if (showChooseAgain) {
                OutlinedButton(onClick = onChooseAgain) {
                    Text("Choose PDF Again")
                }
            }
        }
    }
}
