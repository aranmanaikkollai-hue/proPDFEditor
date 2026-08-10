package com.propdf.viewer.ui

import android.net.Uri
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
    val uri = remember(documentUri) { Uri.parse(documentUri) }
    val documentId = remember(documentUri) { documentUri.hashCode().toString() }

    val viewerViewModel: PDFViewerViewModel = hiltViewModel()
    val annotationViewModel: AnnotationViewModel = hiltViewModel()

    val viewerState by viewerViewModel.viewerState.collectAsState()
    val currentPage by viewerViewModel.currentPage.collectAsState()
    val totalPages by viewerViewModel.totalPages.collectAsState()
    val zoomLevel by viewerViewModel.zoomLevel.collectAsState()
    val isLoading by viewerViewModel.isLoading.collectAsState()
    val thumbnails by viewerViewModel.thumbnails.collectAsState()
    val errorMessage by viewerViewModel.errorMessage.collectAsState()
    val currentPageBitmap by viewerViewModel.currentPageBitmap.collectAsState()

    var showSidebar by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var annotationMode by remember { mutableStateOf(startInAnnotationMode) }
    var showBottomSheet by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Initialize viewer
    LaunchedEffect(uri) {
        viewerViewModel.openDocument(
            uri = uri,
            documentId = documentId,
            cacheDir = context.cacheDir
        )
        if (initialPage > 0) {
            viewerViewModel.goToPage(initialPage)
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
                    ErrorState(
                        message = errorMessage ?: "Failed to load PDF",
                        onRetry = {
                            viewerViewModel.openDocument(uri, documentId, context.cacheDir)
                        }
                    )
                }
                else -> {
                    PDFCanvas(
                        pageBitmap = currentPageBitmap,
                        zoomLevel = zoomLevel,
                        onZoomChange = { viewerViewModel.updateZoom(it) },
                        onViewportChange = { l, t, r, b ->
                            viewerViewModel.updateViewport(l, t, r, b)
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (annotationMode) {
                        AnnotationOverlay(
                            viewModel = annotationViewModel,
                            pageIndex = currentPage,
                            pageWidth = 612f, // Standard PDF width
                            pageHeight = 792f, // Standard PDF height
                            pageScale = zoomLevel,
                            pageOffset = androidx.compose.ui.geometry.Offset.Zero,
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
private fun ErrorState(message: String, onRetry: () -> Unit) {
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
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}
