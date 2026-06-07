package com.propdf.viewer.ui

import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.viewer.model.SearchResult
import com.propdf.viewer.model.ThumbnailPage
import com.propdf.viewer.preload.PreloadManager
import com.propdf.viewer.rendering.BitmapPool
import com.propdf.viewer.rendering.ThumbnailManager
import com.propdf.viewer.rendering.TileGrid
import com.propdf.viewer.rendering.TileRenderer
import com.propdf.viewer.rendering.ViewportManager
import com.propdf.viewer.search.SearchIndex
import com.propdf.viewer.util.MemoryPressureHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject

/**
 * Main ViewModel orchestrating the premium PDF viewer engine.
 *
 * Responsibilities:
 * - Document lifecycle management (open/close)
 * - Viewport tracking and tile grid management
 * - Coordinate rendering, preloading, thumbnails, and search
 * - Memory pressure adaptation
 * - State exposure to Compose UI
 */
@HiltViewModel
class PDFViewerViewModel @Inject constructor(
    private val bitmapPool: BitmapPool,
    private val searchIndex: SearchIndex,
    private val memoryPressureHandler: MemoryPressureHandler
) : ViewModel() {

    companion object {
        private const val TAG = "PDFViewerViewModel"
    }

    // --- State ---
    private val _viewerState = MutableStateFlow<ViewerState>(ViewerState.Idle)
    val viewerState: StateFlow<ViewerState> = _viewerState.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _totalPages = MutableStateFlow(0)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    private val _zoomLevel = MutableStateFlow(1.0f)
    val zoomLevel: StateFlow<Float> = _zoomLevel.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _thumbnails = MutableStateFlow<List<ThumbnailPage>>(emptyList())
    val thumbnails: StateFlow<List<ThumbnailPage>> = _thumbnails.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // --- Core Components ---
    private var pdfRenderer: PdfRenderer? = null
    private var tileRenderer: TileRenderer? = null
    private var preloadManager: PreloadManager? = null
    private var thumbnailManager: ThumbnailManager? = null
    private val viewportManager = ViewportManager()

    /** Page grids: pageIndex -> TileGrid */
    private val pageGrids = mutableMapOf<Int, TileGrid>()
    private val gridsMutex = Mutex()

    /** Current document ID for search indexing */
    private var currentDocumentId: String? = null

    /** Active rendering jobs */
    private val renderJobs = mutableListOf<Job>()

    init {
        // Initialize bitmap pool with device RAM
        val runtime = Runtime.getRuntime()
        val totalRam = runtime.maxMemory()
        bitmapPool.initialize(totalRam)

        // Start memory pressure monitoring
        memoryPressureHandler.startMonitoring()
        memoryPressureHandler.setOnPressureChangeListener { level ->
            viewModelScope.launch {
                when (level) {
                    MemoryPressureHandler.PressureLevel.CRITICAL -> {
                        preloadManager?.setEnabled(false)
                    }
                    MemoryPressureHandler.PressureLevel.NORMAL -> {
                        preloadManager?.setEnabled(true)
                    }
                    else -> { /* no-op */ }
                }
            }
        }
    }

    /**
     * Open a PDF document from a URI.
     */
    fun openDocument(uri: Uri, documentId: String, cacheDir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.update { true }
                _viewerState.update { ViewerState.Loading }

                closeDocumentInternal()

                currentDocumentId = documentId

                val pfd = ParcelFileDescriptor.open(File(uri.path), ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                pdfRenderer = renderer

                val pageCount = renderer.pageCount
                _totalPages.update { pageCount }

                // Initialize tile renderer
                tileRenderer = TileRenderer(bitmapPool, renderer, pageCount)

                // Initialize preload manager
                preloadManager = PreloadManager(tileRenderer!!, viewModelScope)

                // Initialize thumbnail manager
                thumbnailManager = ThumbnailManager(bitmapPool, renderer, cacheDir)

                // Build page grids
                buildPageGrids(renderer)

                // Start thumbnail generation
                launchThumbnailGeneration()

                _viewerState.update { ViewerState.Ready }
                _isLoading.update { false }

            } catch (e: Exception) {
                _errorMessage.update { "Failed to open document: ${e.message}" }
                _viewerState.update { ViewerState.Error(e.message ?: "Unknown error") }
                _isLoading.update { false }
            }
        }
    }

    /**
     * Close the current document and release all resources.
     */
    fun closeDocument() {
        viewModelScope.launch {
            closeDocumentInternal()
        }
    }

    private suspend fun closeDocumentInternal() {
        renderJobs.forEach { it.cancel() }
        renderJobs.clear()

        preloadManager?.cancelAll()
        preloadManager = null

        thumbnailManager?.clearAllCaches()
        thumbnailManager = null

        tileRenderer?.clearCache()
        tileRenderer = null

        pdfRenderer?.close()
        pdfRenderer = null

        pageGrids.clear()

        currentDocumentId?.let { docId ->
            searchIndex.clearDocumentIndex(docId)
        }
        currentDocumentId = null

        _viewerState.update { ViewerState.Idle }
        _currentPage.update { 0 }
        _totalPages.update { 0 }
        _searchResults.update { emptyList() }
        _thumbnails.update { emptyList() }
    }

    /**
     * Update viewport and trigger tile rendering.
     */
    fun updateViewport(left: Int, top: Int, right: Int, bottom: Int) {
        viewportManager.updateViewport(left, top, right, bottom)
        updateCurrentPageFromScroll(top)
        scheduleTileRender()
    }

    /**
     * Update zoom level and rebuild grids.
     */
    fun updateZoom(zoom: Float) {
        viewportManager.updateZoom(zoom)
        _zoomLevel.update { zoom }

        viewModelScope.launch {
            gridsMutex.withLock {
                pageGrids.values.forEach { it.updateZoom(zoom) }
            }
            scheduleTileRender()
        }
    }

    /**
     * Navigate to a specific page.
     */
    fun goToPage(pageIndex: Int) {
        val clamped = pageIndex.coerceIn(0, (_totalPages.value - 1).coerceAtLeast(0))
        _currentPage.update { clamped }
        viewportManager.updateCurrentPage(clamped)
        // UI layer should scroll to this page position
    }

    /**
     * Search within the current document.
     */
    fun search(query: String) {
        val docId = currentDocumentId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.update { true }
                val results = searchIndex.search(docId, query)
                _searchResults.update { results }
                _isLoading.update { false }
            } catch (e: Exception) {
                _errorMessage.update { "Search failed: ${e.message}" }
                _isLoading.update { false }
            }
        }
    }

    /**
     * Index the current document for search.
     * Extracts text from all pages.
     */
    fun indexDocumentForSearch(pageTexts: Map<Int, String>) {
        val docId = currentDocumentId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            searchIndex.indexDocument(docId, pageTexts)
        }
    }

    /**
     * Get recent searches for current document.
     */
    fun getRecentSearches(): StateFlow<List<String>> {
        val flow = MutableStateFlow<List<String>>(emptyList())
        val docId = currentDocumentId ?: return flow
        viewModelScope.launch(Dispatchers.IO) {
            flow.update { searchIndex.getRecentSearches(docId) }
        }
        return flow
    }

    private fun buildPageGrids(renderer: PdfRenderer) {
        viewModelScope.launch {
            gridsMutex.withLock {
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    val width = page.width.toFloat()
                    val height = page.height.toFloat()
                    page.close()

                    pageGrids[i] = TileGrid(i, width, height, 2.0f) // density
                }
            }
        }
    }

    private fun scheduleTileRender() {
        // Cancel previous render jobs
        renderJobs.forEach { it.cancel() }
        renderJobs.clear()

        val currentPage = _currentPage.value
        val zoom = _zoomLevel.value
        val viewport = viewportManager.viewport.value

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val grid = gridsMutex.withLock { pageGrids[currentPage] } ?: return@launch
                grid.updateZoom(zoom)

                val visibleTiles = grid.getVisibleTiles(viewport)

                // Render visible tiles
                visibleTiles.forEach { tile ->
                    val job = launch {
                        tileRenderer?.renderTile(tile)
                    }
                    renderJobs.add(job)
                }

                // Trigger preloading
                preloadManager?.queuePreload(
                    visibleTiles,
                    pageGrids,
                    currentPage,
                    _totalPages.value
                )

            } catch (e: CancellationException) {
                // Expected on cancellation
            }
        }
    }

    private fun launchThumbnailGeneration() {
        viewModelScope.launch(Dispatchers.Default) {
            val manager = thumbnailManager ?: return@launch
            val pageCount = _totalPages.value

            // Generate thumbnails in batches
            for (start in 0 until pageCount step 10) {
                val end = minOf(start + 9, pageCount - 1)
                manager.generateThumbnailsRange(start, end).collect { thumb ->
                    _thumbnails.update { current ->
                        current + thumb
                    }
                }
            }
        }
    }

    private fun updateCurrentPageFromScroll(scrollY: Int) {
        // Calculate current page based on scroll position
        // This is a simplified version; real implementation would track page heights
        val pageCount = _totalPages.value
        if (pageCount == 0) return
        // Approximate page from scroll
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            closeDocumentInternal()
            memoryPressureHandler.stopMonitoring()
        }
    }

    sealed class ViewerState {
        object Idle : ViewerState()
        object Loading : ViewerState()
        object Ready : ViewerState()
        data class Error(val message: String) : ViewerState()
    }
}
