package com.propdf.viewer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject

/**
 * Main ViewModel orchestrating the premium PDF viewer engine.
 */
@HiltViewModel
class PDFViewerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val bitmapPool: BitmapPool,
    private val searchIndex: SearchIndex,
    private val memoryPressureHandler: MemoryPressureHandler
) : ViewModel() {

    companion object {
        private const val TAG = "PDFViewerViewModel"
    }

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

    // The tile-based rendering pipeline below (TileRenderer/TileGrid/
    // BitmapPool) builds tiles via scheduleTileRender(), but nothing ever
    // read the results back out into the UI -- PDFCanvas was drawing a
    // hardcoded gray/dark-gray checkerboard placeholder unconditionally,
    // which is the "pixel blocks instead of the image" bug. Rather than
    // rewire the whole tile grid (viewport-to-tile mapping, per-tile
    // bitmaps, etc.), this renders the current page directly with
    // PdfRenderer and exposes it as a single bitmap the canvas can draw.
    private val _currentPageBitmap = MutableStateFlow<Bitmap?>(null)
    val currentPageBitmap: StateFlow<Bitmap?> = _currentPageBitmap.asStateFlow()

    private val renderMutex = Mutex()

    private var pdfRenderer: PdfRenderer? = null
    private var tileRenderer: TileRenderer? = null
    private var preloadManager: PreloadManager? = null
    private var thumbnailManager: ThumbnailManager? = null
    private val viewportManager = ViewportManager()

    private val pageGrids = mutableMapOf<Int, TileGrid>()
    private val gridsMutex = Mutex()

    private var currentDocumentId: String? = null
    private val renderJobs = mutableListOf<Job>()

    init {
        val runtime = Runtime.getRuntime()
        val totalRam = runtime.maxMemory()
        bitmapPool.initialize(totalRam)

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
                    else -> {}
                }
            }
        }
    }

    fun openDocument(uri: Uri, documentId: String, cacheDir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.update { true }
                _viewerState.update { ViewerState.Loading }

                closeDocumentInternal()

                currentDocumentId = documentId

                // "file" URIs point at a real filesystem path, so they can be
                // opened directly. Everything else (content:// from SAF,
                // MediaStore, FileProvider, etc.) does NOT have a usable
                // filesystem path in uri.path -- that previously caused
                // ParcelFileDescriptor.open(File(uri.path)) to throw
                // ENOENT ("No such file or directory") because uri.path for
                // a content:// Uri is just an opaque provider segment, not a
                // real path on disk. Those must go through the
                // ContentResolver instead.
                val pfd = if (uri.scheme == "file") {
                    val path = uri.path
                    if (path == null || !File(path).exists()) {
                        _errorMessage.update { "Failed to open document: file not found" }
                        _viewerState.update { ViewerState.Error("File not found") }
                        _isLoading.update { false }
                        return@launch
                    }
                    ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                } else {
                    appContext.contentResolver.openFileDescriptor(uri, "r")
                }

                if (pfd == null) {
                    _errorMessage.update { "Failed to open document: unable to access content" }
                    _viewerState.update { ViewerState.Error("Unable to access content") }
                    _isLoading.update { false }
                    return@launch
                }

                val renderer = PdfRenderer(pfd)
                pdfRenderer = renderer

                val pageCount = renderer.pageCount
                _totalPages.update { pageCount }

                tileRenderer = TileRenderer(bitmapPool, renderer, pageCount)
                preloadManager = PreloadManager(tileRenderer!!, viewModelScope)
                thumbnailManager = ThumbnailManager(bitmapPool, renderer, cacheDir)

                buildPageGrids(renderer)
                launchThumbnailGeneration()

                _viewerState.update { ViewerState.Ready }
                _isLoading.update { false }

                renderCurrentPage()

            } catch (e: Exception) {
                _errorMessage.update { "Failed to open document: ${e.message}" }
                _viewerState.update { ViewerState.Error(e.message ?: "Unknown error") }
                _isLoading.update { false }
            }
        }
    }

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

        _currentPageBitmap.update { old ->
            old?.takeIf { !it.isRecycled }?.recycle()
            null
        }

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

    fun updateViewport(left: Int, top: Int, right: Int, bottom: Int) {
        viewportManager.updateViewport(left, top, right, bottom)
        updateCurrentPageFromScroll(top)
        scheduleTileRender()
    }

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

    fun goToPage(pageIndex: Int) {
        val clamped = pageIndex.coerceIn(0, (_totalPages.value - 1).coerceAtLeast(0))
        _currentPage.update { clamped }
        viewportManager.updateCurrentPage(clamped)
        renderCurrentPage()
    }

    /**
     * Renders the current page from the live PdfRenderer into a single
     * bitmap sized to the device's display width, and publishes it via
     * [currentPageBitmap] for PDFCanvas to draw.
     */
    private fun renderCurrentPage() {
        val renderer = pdfRenderer ?: return
        val pageIndex = _currentPage.value
        viewModelScope.launch(Dispatchers.Default) {
            renderMutex.withLock {
                try {
                    if (pageIndex !in 0 until renderer.pageCount) return@withLock
                    val page = renderer.openPage(pageIndex)
                    val targetWidth = appContext.resources.displayMetrics.widthPixels
                        .coerceAtLeast(1)
                    val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                    val bw = (page.width * scale).toInt().coerceAtLeast(1)
                    val bh = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    _currentPageBitmap.update { old ->
                        old?.takeIf { !it.isRecycled }?.recycle()
                        bitmap
                    }
                } catch (e: Exception) {
                    // Keep whatever was showing rather than clearing to blank
                    // on a transient render failure.
                }
            }
        }
    }

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

    fun indexDocumentForSearch(pageTexts: Map<Int, String>) {
        val docId = currentDocumentId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            searchIndex.indexDocument(docId, pageTexts)
        }
    }

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

                    pageGrids[i] = TileGrid(i, width, height, 2.0f)
                }
            }
        }
    }

    private fun scheduleTileRender() {
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

                visibleTiles.forEach { tile ->
                    val job = launch {
                        tileRenderer?.renderTile(tile)
                    }
                    renderJobs.add(job)
                }

                preloadManager?.queuePreload(
                    visibleTiles,
                    pageGrids,
                    currentPage,
                    _totalPages.value
                )

            } catch (e: CancellationException) {
                // Expected
            }
        }
    }

    private fun launchThumbnailGeneration() {
        viewModelScope.launch(Dispatchers.Default) {
            val manager = thumbnailManager ?: return@launch
            val pageCount = _totalPages.value

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
        val pageCount = _totalPages.value
        if (pageCount == 0) return
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
