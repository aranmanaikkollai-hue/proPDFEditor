package com.propdf.viewer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.SizeF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.usecase.OpenDocumentUseCase
import com.propdf.viewer.model.SearchResult
import com.propdf.viewer.model.ThumbnailPage
import com.propdf.viewer.model.Tile
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
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Main ViewModel orchestrating the premium PDF viewer engine.
 */
@HiltViewModel
class PDFViewerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val bitmapPool: BitmapPool,
    private val searchIndex: SearchIndex,
    private val memoryPressureHandler: MemoryPressureHandler,
    private val openDocumentUseCase: OpenDocumentUseCase
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
    // BitmapPool) builds tiles via scheduleTileRender(), and PDFCanvas can
    // now draw completed tiles (see renderedTiles) as a progressive-quality
    // layer on top of this full-page fallback bitmap. The fallback keeps a
    // page visible immediately/at all zoom levels even before tiles for the
    // current viewport have finished rendering.
    private val _currentPageBitmap = MutableStateFlow<Bitmap?>(null)
    val currentPageBitmap: StateFlow<Bitmap?> = _currentPageBitmap.asStateFlow()

    // Real PDF page dimensions (in PDF points, i.e. PdfRenderer.Page.width/height)
    // for the page currently represented by currentPageBitmap. Published
    // together with the bitmap update so a consumer never observes a bitmap
    // for one page next to dimensions belonging to a different page.
    private val _currentPageSize = MutableStateFlow(SizeF(0f, 0f))
    val currentPageSize: StateFlow<SizeF> = _currentPageSize.asStateFlow()

    // Fine-grained lifecycle for the *current page* render operation, distinct
    // from the document-level viewerState. Lets the UI show a real "rendering"
    // indicator instead of an indefinite placeholder, without conflating a
    // single page's render failure with the whole document failing to open.
    private val _pageRenderState = MutableStateFlow<PageRenderState>(PageRenderState.Idle)
    val pageRenderState: StateFlow<PageRenderState> = _pageRenderState.asStateFlow()

    // Tiles for the currently visible viewport that have finished rendering.
    // PDFCanvas can draw these over the fallback bitmap for sharper detail;
    // the fallback bitmap remains the source of truth so a gap in tile
    // coverage never shows a hole.
    private val _renderedTiles = MutableStateFlow<List<Tile>>(emptyList())
    val renderedTiles: StateFlow<List<Tile>> = _renderedTiles.asStateFlow()

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

    // Bumped every time a document is closed/replaced. In-flight coroutines
    // from a previous document capture this value before their IO/render
    // work and check it again afterward; a mismatch means a newer document
    // (or a newer render request for a different page) has since taken over,
    // so results are discarded instead of stomping on the current state.
    private val documentGeneration = AtomicInteger(0)

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
                // Close whatever was previously open first -- closing
                // resets viewerState to Idle, so Loading must be set
                // afterward or it would immediately be clobbered back to
                // Idle and the UI would show a blank screen instead of a
                // loading indicator while the new document is being opened.
                closeDocumentInternal()
                val myGeneration = documentGeneration.get()

                _isLoading.update { true }
                _errorMessage.update { null }
                _viewerState.update { ViewerState.Loading }
                Log.i(TAG, "DOCUMENT_OPEN_START")

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
                val pfd: ParcelFileDescriptor? = try {
                    if (uri.scheme == "file") {
                        val path = uri.path
                        if (path == null || !File(path).exists()) {
                            failDocumentOpen(
                                ViewerState.Error(
                                    "This PDF could not be found. It may have been moved or deleted.",
                                    isAccessError = true
                                )
                            )
                            return@launch
                        }
                        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                    } else {
                        appContext.contentResolver.openFileDescriptor(uri, "r")
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "DOCUMENT_PERMISSION_FAILURE", e)
                    failDocumentOpen(classifyOpenFailure(e))
                    return@launch
                } catch (e: FileNotFoundException) {
                    failDocumentOpen(classifyOpenFailure(e))
                    return@launch
                } catch (e: IOException) {
                    failDocumentOpen(classifyOpenFailure(e))
                    return@launch
                }

                if (pfd == null) {
                    failDocumentOpen(
                        ViewerState.Error(
                            "Unable to access this PDF. File access has expired or the file is no longer available.",
                            isAccessError = true
                        )
                    )
                    return@launch
                }

                val renderer = try {
                    PdfRenderer(pfd)
                } catch (e: SecurityException) {
                    pfd.close()
                    Log.w(TAG, "DOCUMENT_PERMISSION_FAILURE", e)
                    failDocumentOpen(classifyOpenFailure(e))
                    return@launch
                } catch (e: IOException) {
                    // Thrown by PdfRenderer for malformed/unreadable PDFs.
                    pfd.close()
                    failDocumentOpen(classifyOpenFailure(e))
                    return@launch
                }

                if (documentGeneration.get() != myGeneration) {
                    // A newer document was opened while we were still setting
                    // this one up (e.g. rapid successive opens). Abandon this
                    // one cleanly rather than letting it become "current".
                    renderer.close()
                    return@launch
                }

                pdfRenderer = renderer

                val pageCount = renderer.pageCount
                _totalPages.update { pageCount }

                tileRenderer = TileRenderer(bitmapPool, renderer, pageCount)
                preloadManager = PreloadManager(tileRenderer!!, viewModelScope)
                thumbnailManager = ThumbnailManager(bitmapPool, renderer, cacheDir)

                buildPageGrids(renderer, myGeneration)
                launchThumbnailGeneration()

                _viewerState.update { ViewerState.Ready }
                _isLoading.update { false }
                Log.i(TAG, "DOCUMENT_OPEN_SUCCESS")

                renderCurrentPage()

            } catch (e: Exception) {
                failDocumentOpen(classifyOpenFailure(e))
            }
        }
    }

    /**
     * Re-opens the viewer with a newly picked URI after the previous one
     * became inaccessible (expired/revoked SAF grant, moved/deleted file,
     * etc.). Reuses the existing document-opening pipeline and best-effort
     * registers/updates the recent-files entry for the new URI via the
     * existing OpenDocumentUseCase, without inventing a second picker or
     * persistence path.
     */
    fun reopenWithReplacementUri(uri: Uri, documentId: String, cacheDir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                openDocumentUseCase(uri)
            } catch (e: Exception) {
                // Best-effort only; failing to update the recent-files
                // record should not block the user from viewing the file.
                Log.w(TAG, "Failed to update recent-file record for replacement URI", e)
            }
        }
        openDocument(uri, documentId, cacheDir)
    }

    private fun failDocumentOpen(state: ViewerState.Error) {
        _errorMessage.update { state.message }
        _viewerState.update { state }
        _isLoading.update { false }
    }

    private fun classifyOpenFailure(e: Throwable): ViewerState.Error = when (e) {
        is SecurityException -> ViewerState.Error(
            "Unable to access this PDF. File access has expired or is no longer available.",
            isAccessError = true
        )
        is FileNotFoundException -> ViewerState.Error(
            "This PDF could not be found. It may have been moved or deleted.",
            isAccessError = true
        )
        is IOException -> ViewerState.Error(
            "Unable to read this PDF. The file may be corrupted or unsupported.",
            isAccessError = false
        )
        else -> ViewerState.Error(
            "Unable to open this PDF.",
            isAccessError = false
        )
    }

    fun closeDocument() {
        viewModelScope.launch {
            closeDocumentInternal()
        }
    }

    private suspend fun closeDocumentInternal() {
        // Invalidate any in-flight work tied to the document being closed.
        documentGeneration.incrementAndGet()

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
        _currentPageSize.update { SizeF(0f, 0f) }
        _renderedTiles.update { emptyList() }
        _pageRenderState.update { PageRenderState.Idle }

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
        _errorMessage.update { null }

        // A new document starts from a clean viewport/scale, not whatever
        // the previous document was zoomed/panned to.
        _zoomLevel.update { 1.0f }
        viewportManager.updateZoom(1.0f)
        viewportManager.updateCurrentPage(0)

        Log.i(TAG, "DOCUMENT_CLOSED")
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
        val previousPage = _currentPage.value
        if (clamped == previousPage) return

        _currentPage.update { clamped }
        viewportManager.updateCurrentPage(clamped)

        // A page navigation invalidates tiles queued/rendering for the page
        // being left, and the viewport-relative pan the user had on that
        // page no longer means anything on the new one.
        _renderedTiles.update { emptyList() }
        viewModelScope.launch {
            tileRenderer?.cancelPage(previousPage)
        }

        renderCurrentPage()
    }

    /**
     * Renders the current page from the live PdfRenderer into a single
     * bitmap sized to the device's display width, and publishes it via
     * [currentPageBitmap] for PDFCanvas to draw.
     *
     * Every invocation has an explicit lifecycle: RENDERING -> SUCCESS or
     * RENDERING -> FAILURE. A render is never left silently "in progress"
     * forever -- on any real failure (not cancellation) both
     * [pageRenderState] and the document-level [viewerState] reach an Error
     * state so the UI can stop showing a loading indicator.
     */
    private fun renderCurrentPage() {
        val renderer = pdfRenderer ?: return
        val pageIndex = _currentPage.value
        val myGeneration = documentGeneration.get()

        _pageRenderState.update { PageRenderState.Rendering }
        Log.i(TAG, "DOCUMENT_RENDER_START")

        val job = viewModelScope.launch(Dispatchers.Default) {
            renderMutex.withLock {
                // Stale by the time we acquired the lock -- a newer
                // document or page navigation has already superseded this
                // request.
                if (documentGeneration.get() != myGeneration || _currentPage.value != pageIndex) {
                    return@withLock
                }
                if (pageIndex !in 0 until renderer.pageCount) {
                    return@withLock
                }

                var page: PdfRenderer.Page? = null
                try {
                    page = renderer.openPage(pageIndex)
                    val pageWidthPts = page.width.toFloat()
                    val pageHeightPts = page.height.toFloat()

                    val targetWidth = appContext.resources.displayMetrics.widthPixels
                        .coerceAtLeast(1)
                    val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                    var bw = (page.width * scale).toInt().coerceAtLeast(1)
                    var bh = (page.height * scale).toInt().coerceAtLeast(1)

                    val bitmap = try {
                        Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    } catch (oom: OutOfMemoryError) {
                        // Recovery path: free pooled memory and retry once at
                        // half resolution rather than crashing the app.
                        Log.w(TAG, "DOCUMENT_RENDER_OOM: retrying at reduced resolution")
                        bitmapPool.trim(1.0f)
                        System.gc()
                        bw = (bw / 2).coerceAtLeast(1)
                        bh = (bh / 2).coerceAtLeast(1)
                        Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    }

                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    // Re-check staleness after the (potentially slow) render
                    // completed: don't let a late-finishing render for a page
                    // the user has since navigated away from overwrite what
                    // is currently on screen (Document A / Document B and
                    // Page 1 / Page 2 stale-render protection).
                    if (documentGeneration.get() != myGeneration || _currentPage.value != pageIndex) {
                        bitmap.recycle()
                        return@withLock
                    }

                    _currentPageSize.update { SizeF(pageWidthPts, pageHeightPts) }
                    _currentPageBitmap.update { old ->
                        old?.takeIf { !it.isRecycled }?.recycle()
                        bitmap
                    }
                    _pageRenderState.update { PageRenderState.Success }
                    Log.i(TAG, "DOCUMENT_RENDER_SUCCESS")
                } catch (e: CancellationException) {
                    // Expected during rapid navigation/zoom; not a
                    // user-visible failure.
                    throw e
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "DOCUMENT_RENDER_OOM", e)
                    bitmapPool.trim(1.0f)
                    System.gc()
                    _pageRenderState.update {
                        PageRenderState.Error("Not enough memory to render this page.")
                    }
                    _viewerState.update {
                        ViewerState.Error(
                            "Not enough memory to render this page. Try closing other apps.",
                            isAccessError = false
                        )
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "DOCUMENT_PERMISSION_FAILURE during render", e)
                    _pageRenderState.update {
                        PageRenderState.Error("Access to this document was lost.")
                    }
                    _viewerState.update {
                        ViewerState.Error(
                            "Unable to access this PDF. File access has expired or is no longer available.",
                            isAccessError = true
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "DOCUMENT_RENDER_FAILURE", e)
                    _pageRenderState.update {
                        PageRenderState.Error("Failed to render this page.")
                    }
                    _viewerState.update {
                        ViewerState.Error("Failed to render this page.", isAccessError = false)
                    }
                } finally {
                    page?.close()
                }
            }
        }
        renderJobs.add(job)
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

    private fun buildPageGrids(renderer: PdfRenderer, myGeneration: Int) {
        viewModelScope.launch {
            gridsMutex.withLock {
                if (documentGeneration.get() != myGeneration) return@withLock
                for (i in 0 until renderer.pageCount) {
                    if (documentGeneration.get() != myGeneration) return@withLock
                    var page: PdfRenderer.Page? = null
                    try {
                        page = renderer.openPage(i)
                        val width = page.width.toFloat()
                        val height = page.height.toFloat()
                        pageGrids[i] = TileGrid(i, width, height, 2.0f)
                    } finally {
                        page?.close()
                    }
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
        val myGeneration = documentGeneration.get()

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val grid = gridsMutex.withLock { pageGrids[currentPage] } ?: return@launch
                grid.updateZoom(zoom)

                val visibleTiles = grid.getVisibleTiles(viewport)

                visibleTiles.forEach { tile ->
                    val job = launch {
                        tileRenderer?.renderTile(tile)
                        if (documentGeneration.get() == myGeneration && _currentPage.value == currentPage) {
                            publishRenderedTile(tile)
                        }
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

    /** Merges a freshly-rendered tile into [renderedTiles], replacing any stale entry for the same tile id. */
    private fun publishRenderedTile(tile: Tile) {
        if (tile.bitmapRef == null) return
        _renderedTiles.update { current ->
            (current.filterNot { it.id == tile.id } + tile)
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

        /**
         * @param isAccessError true when the failure stems from a
         * permission/access problem (revoked SAF grant, deleted/moved file,
         * unreadable provider) rather than a corrupt/unsupported PDF. The UI
         * uses this to decide whether to offer "Choose PDF Again" alongside
         * "Retry".
         */
        data class Error(val message: String, val isAccessError: Boolean = false) : ViewerState()
    }

    sealed class PageRenderState {
        object Idle : PageRenderState()
        object Rendering : PageRenderState()
        object Success : PageRenderState()
        data class Error(val message: String) : PageRenderState()
    }
}
