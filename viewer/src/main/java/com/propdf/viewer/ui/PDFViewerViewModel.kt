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
import kotlinx.coroutines.delay
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
import java.util.Collections
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

        // Vertical gap between consecutive pages in the continuous document
        // viewport, in the same "document units" as PageLayout (PDF points
        // at the shared layout width -- see buildPageLayoutsAndGrids).
        private const val PAGE_GAP_PTS = 16f

        // How many pages beyond the current page (on each side) are kept
        // rendered/cached at once, so scrolling to an adjacent page shows
        // real content immediately instead of a blank page boundary.
        private const val PAGE_WINDOW = 1

        // Minimum margin (in document points) by which a candidate page must
        // be closer to the viewport center than the current page before
        // currentPage actually switches. See updateDocumentScroll for why.
        private const val PAGE_SWITCH_HYSTERESIS_PTS = 4f
    }

    /**
     * A single page's position and size within the continuous document
     * layout. [docTop]/[docHeight] are in a shared "document unit" space
     * (the first page's PDF point width is used as the common layout width,
     * so every page -- regardless of its own real width/height or
     * orientation -- occupies the same on-screen column width, stacked
     * top-to-bottom with [PAGE_GAP_PTS] between pages, exactly like a normal
     * document viewport rather than a floating per-page bitmap).
     */
    data class PageLayout(
        val index: Int,
        val widthPts: Float,
        val heightPts: Float,
        val docTop: Float,
        val docHeight: Float
    )

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

    // --- Continuous document viewport model -------------------------------
    // The document is laid out as a single vertical column of pages (see
    // PageLayout) rather than treated as one floating full-page bitmap.
    // documentScrollY is the single authoritative scroll position, in the
    // same document-unit space as PageLayout.docTop/docHeight; the UI
    // mirrors it into local Compose state for smooth gesture feedback and
    // reports gesture-driven changes back via updateDocumentScroll/goToPage,
    // but this ViewModel remains the source of truth.

    private val _pageLayouts = MutableStateFlow<List<PageLayout>>(emptyList())
    val pageLayouts: StateFlow<List<PageLayout>> = _pageLayouts.asStateFlow()

    // The shared document-unit width every page is laid out at (the first
    // page's real PDF point width). Other pages keep their own real aspect
    // ratio scaled to this width (see buildPageLayoutsAndGrids), so mixed
    // page sizes/orientations stack into one non-overlapping column.
    private val _layoutWidth = MutableStateFlow(0f)
    val layoutWidth: StateFlow<Float> = _layoutWidth.asStateFlow()

    private val _documentHeight = MutableStateFlow(0f)
    val documentHeight: StateFlow<Float> = _documentHeight.asStateFlow()

    private val _documentScrollY = MutableStateFlow(0f)
    val documentScrollY: StateFlow<Float> = _documentScrollY.asStateFlow()

    // A small window of rendered page bitmaps (current page +/- PAGE_WINDOW)
    // keyed by page index, so scrolling to an adjacent page shows real
    // content instead of just a blank page-shaped placeholder, without
    // holding the whole document's bitmaps in memory at once.
    private val _pageBitmaps = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())
    val pageBitmaps: StateFlow<Map<Int, Bitmap>> = _pageBitmaps.asStateFlow()

    private val renderMutex = Mutex()

    private var pdfRenderer: PdfRenderer? = null
    private var tileRenderer: TileRenderer? = null
    private var preloadManager: PreloadManager? = null
    private var thumbnailManager: ThumbnailManager? = null
    private val viewportManager = ViewportManager()

    private val pageGrids = mutableMapOf<Int, TileGrid>()
    private val gridsMutex = Mutex()

    private var currentDocumentId: String? = null
    // A viewport update is frequent while scrolling/pinching. It must only
    // replace obsolete tile work, never cancel the full-page fallback render
    // that owns the visible page's Rendering -> Success/Error transition.
    private val pageRenderJobs = Collections.synchronizedList(mutableListOf<Job>())
    private val tileRenderJobs = Collections.synchronizedList(mutableListOf<Job>())

    // The scale multiplier (relative to screen-width resolution) that each
    // currently-cached page bitmap in [_pageBitmaps] was actually rendered
    // at. renderPage() was previously always rendering at a fixed
    // screen-width resolution and skipping any page already in the cache --
    // so pinch-zooming in just stretched that fixed-resolution bitmap larger
    // via drawImage's dstSize, producing visibly blurry pages at any zoom
    // above ~1x, even though a full tile-rendering pipeline
    // (TileRenderer/TileGrid) already existed to solve exactly this and was
    // computing tiles that PDFCanvas never actually draws. Fully rewiring
    // PDFCanvas onto that generic tile grid is a larger, riskier change,
    // so the smaller, safe fix here is to let the current page's fallback
    // bitmap itself be re-rendered at a higher resolution once the user has
    // zoomed in far enough that the screen-width bitmap would visibly blur.
    private val pageBitmapScale = mutableMapOf<Int, Float>()
    private var zoomRenderJob: Job? = null

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

    fun openDocument(uri: Uri, documentId: String, cacheDir: File, initialPage: Int = 0) {
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
                } catch (e: OutOfMemoryError) {
                    // PdfRenderer construction itself can allocate native
                    // memory. Convert that failure into a recoverable viewer
                    // error and release the descriptor we still own.
                    pfd.close()
                    bitmapPool.trim(1.0f)
                    failDocumentOpen(
                        ViewerState.Error(
                            "Not enough memory to open this PDF. Try closing other apps.",
                            isAccessError = false
                        )
                    )
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

                tileRenderer = TileRenderer(bitmapPool, renderer, pageCount, renderMutex)
                preloadManager = PreloadManager(tileRenderer!!, viewModelScope)
                thumbnailManager = ThumbnailManager(bitmapPool, renderer, cacheDir)

                buildPageLayoutsAndGridsSuspend(renderer, myGeneration)
                launchThumbnailGeneration()

                if (documentGeneration.get() != myGeneration) return@launch

                _viewerState.update { ViewerState.Ready }
                _isLoading.update { false }
                Log.i(TAG, "DOCUMENT_OPEN_SUCCESS")

                // Deterministic initial position: requested page (default
                // page 0), top of that page, no inherited pan/zoom from any
                // previous document. Page layouts are already built at this
                // point (awaited above), so the target page's document-space
                // position is known and the scroll jump lands correctly on
                // the first frame instead of racing the layout computation.
                val startPage = initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                _currentPage.update { startPage }
                pageLayoutFor(startPage)?.let { layout ->
                    _documentScrollY.update { layout.docTop }
                }
                renderPage(startPage)
                renderPage(startPage - 1)
                renderPage(startPage + 1)

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

        pageRenderJobs.toList().forEach { it.cancel() }
        pageRenderJobs.clear()
        tileRenderJobs.toList().forEach { it.cancel() }
        tileRenderJobs.clear()

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

        // Continuous document viewport state must not leak into the next
        // document: no inherited scroll position, no inherited page window.
        _pageBitmaps.update { current ->
            current.values.forEach { bmp -> if (!bmp.isRecycled) bmp.recycle() }
            emptyMap()
        }
        pageBitmapScale.clear()
        zoomRenderJob?.cancel()
        _pageLayouts.update { emptyList() }
        _layoutWidth.update { 0f }
        _documentHeight.update { 0f }
        _documentScrollY.update { 0f }

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
        // Retained for the tile-preparation pipeline (TileGrid/TileRenderer),
        // which still operates on a per-page pixel viewport rect. The
        // document-level scroll position is now tracked separately via
        // updateDocumentScroll/documentScrollY.
        viewportManager.updateViewport(left, top, right, bottom)
        scheduleTileRender()
    }

    fun updateZoom(zoom: Float) {
        val clamped = zoom.coerceIn(0.25f, 10.0f)
        viewportManager.updateZoom(clamped)
        _zoomLevel.update { clamped }

        viewModelScope.launch {
            gridsMutex.withLock {
                pageGrids.values.forEach { it.updateZoom(clamped) }
            }
            scheduleTileRender()
        }

        // Debounced: a pinch gesture calls updateZoom on every frame, and a
        // higher-resolution render is real decode+allocation work, not
        // something to repeat on every intermediate zoom value while the
        // user's fingers are still moving.
        val targetMultiplier = scaleMultiplierFor(clamped)
        zoomRenderJob?.cancel()
        zoomRenderJob = viewModelScope.launch {
            delay(250)
            renderPage(_currentPage.value, minScaleMultiplier = targetMultiplier)
        }
    }

    /** Maps a live zoom level to the resolution multiplier the current-page fallback bitmap should be rendered at. */
    private fun scaleMultiplierFor(zoom: Float): Float = when {
        zoom > 3f -> 4f
        zoom > 1.5f -> 2f
        else -> 1f
    }

    /**
     * Authoritative update of the document scroll position, driven by the
     * UI's pan/scroll gestures (see PDFCanvas). [scrollY] and
     * [viewportHeightPts] are both in the same document-unit space as
     * [PageLayout]. Determines the current page from whichever page's
     * center is nearest the viewport's center, only actually switching
     * [currentPage] when a different page becomes nearest -- so it does not
     * thrash on small scroll deltas within the same page.
     */
    /**
     * Authoritative update of the document scroll position, driven by the
     * UI's pan/scroll gestures (see PDFCanvas). [scrollY] and
     * [viewportHeightPts] are both in the same document-unit space as
     * [PageLayout]. Determines the current page from whichever page's
     * center is nearest the viewport's center, only actually switching
     * [currentPage] when a different page becomes nearest -- so it does not
     * thrash on small scroll deltas within the same page.
     *
     * A small hysteresis margin ([PAGE_SWITCH_HYSTERESIS_PTS]) is required
     * before switching pages: without it, sub-pixel float differences in
     * [scrollY]/[viewportHeightPts] between consecutive frames near a page
     * boundary can make "nearest page" flip back and forth on its own, with
     * no user action. That matters beyond just visual jitter -- currentPage
     * is also the pageIndex new annotations get created under (see
     * AnnotationOverlay), so a spurious flip could silently attach a stroke
     * the user is actively drawing to the wrong page.
     */
    fun updateDocumentScroll(scrollY: Float, viewportHeightPts: Float) {
        val docHeight = _documentHeight.value
        val maxScroll = (docHeight - viewportHeightPts).coerceAtLeast(0f)
        val clamped = scrollY.coerceIn(0f, maxScroll)
        _documentScrollY.update { clamped }

        val layouts = _pageLayouts.value
        if (layouts.isEmpty()) return
        val viewportCenter = clamped + viewportHeightPts / 2f

        val nearest = layouts.minByOrNull {
            kotlin.math.abs((it.docTop + it.docHeight / 2f) - viewportCenter)
        } ?: return
        if (nearest.index == _currentPage.value) return

        val nearestDistance = kotlin.math.abs((nearest.docTop + nearest.docHeight / 2f) - viewportCenter)
        val currentDistance = layouts.getOrNull(_currentPage.value)?.let {
            kotlin.math.abs((it.docTop + it.docHeight / 2f) - viewportCenter)
        } ?: Float.MAX_VALUE

        if (nearestDistance + PAGE_SWITCH_HYSTERESIS_PTS < currentDistance) {
            onCurrentPageChanged(nearest.index)
        }
    }

    fun goToPage(pageIndex: Int) {
        val clampedIndex = pageIndex.coerceIn(0, (_totalPages.value - 1).coerceAtLeast(0))

        // Move the authoritative scroll position to the target page's
        // document-space top; the UI observes documentScrollY and moves its
        // own scroll offset to match, so "Next"/"Previous" actually bring
        // the page into view instead of just swapping a bitmap while the
        // viewport stays put.
        pageLayoutFor(clampedIndex)?.let { layout ->
            _documentScrollY.update { layout.docTop }
        }

        if (clampedIndex != _currentPage.value) {
            onCurrentPageChanged(clampedIndex)
        }
    }

    private fun pageLayoutFor(index: Int): PageLayout? = _pageLayouts.value.getOrNull(index)

    /**
     * Switches the authoritative current page, cancels tile work for the
     * page being left, immediately shows whichever bitmap is already cached
     * for the new page (if any -- e.g. from prefetch) instead of flashing a
     * loading state, and (re-)renders the new current page plus its
     * immediate neighbors so continued scrolling has real content ready.
     */
    private fun onCurrentPageChanged(newPage: Int) {
        val previousPage = _currentPage.value
        _currentPage.update { newPage }
        viewportManager.updateCurrentPage(newPage)

        val cached = _pageBitmaps.value[newPage]
        if (cached != null && !cached.isRecycled) {
            _currentPageBitmap.update { cached }
            _pageRenderState.update { PageRenderState.Success }
            pageLayoutFor(newPage)?.let { layout ->
                _currentPageSize.update { SizeF(layout.widthPts, layout.heightPts) }
            }
        } else {
            _currentPageBitmap.update { null }
        }

        _renderedTiles.update { emptyList() }
        viewModelScope.launch {
            tileRenderer?.cancelPage(previousPage)
        }

        trimPageBitmapWindow()
        // Render the incoming page at whatever resolution the user is
        // currently zoomed to, not always screen-width -- otherwise
        // scrolling to a new page while zoomed in would show it blurry
        // until the next pinch gesture retriggers a high-res render.
        val currentMultiplier = scaleMultiplierFor(_zoomLevel.value)
        renderPage(newPage, minScaleMultiplier = currentMultiplier)
        renderPage(newPage - 1)
        renderPage(newPage + 1)

        scheduleTileRender()
    }

    /** Evicts (and recycles) any cached page bitmap outside the current +/- PAGE_WINDOW range. */
    private fun trimPageBitmapWindow() {
        val current = _currentPage.value
        val keepRange = (current - PAGE_WINDOW)..(current + PAGE_WINDOW)
        _pageBitmaps.update { existing ->
            val toEvict = existing.filterKeys { it !in keepRange }
            if (toEvict.isEmpty()) return@update existing
            toEvict.values.forEach { bmp -> if (!bmp.isRecycled) bmp.recycle() }
            toEvict.keys.forEach { pageBitmapScale.remove(it) }
            existing.filterKeys { it in keepRange }
        }
    }

    /**
     * Renders [pageIndex] from the live PdfRenderer into a bitmap and stores
     * it in the small rendered-page window ([pageBitmaps]), so continuous
     * scrolling shows real content for the current page and its immediate
     * neighbors rather than one floating bitmap.
     *
     * When [pageIndex] is the current page, the result is also mirrored into
     * [currentPageBitmap]/[currentPageSize] (kept for backward
     * compatibility) and drives [pageRenderState]/[viewerState] exactly as a
     * single-page render always did: every invocation has an explicit
     * lifecycle (RENDERING -> SUCCESS or RENDERING -> FAILURE), and a render
     * is never left silently "in progress" forever.
     */
    private fun renderPage(pageIndex: Int, minScaleMultiplier: Float = 1f) {
        val renderer = pdfRenderer ?: return
        if (pageIndex < 0) return
        val myGeneration = documentGeneration.get()
        val isCurrent = pageIndex == _currentPage.value
        // Cap how far above screen-width resolution a single fallback page
        // bitmap will go, so a 10x pinch-zoom can't request an absurdly
        // large allocation (e.g. an 8000px-wide bitmap) and OOM instead of
        // just capping visual sharpness.
        val clampedMultiplier = minScaleMultiplier.coerceIn(1f, 4f)

        val job = viewModelScope.launch(Dispatchers.Default) {
            renderMutex.withLock {
                // Stale by the time we acquired the lock -- a newer
                // document has since taken over. A page already cached at
                // this resolution or higher (e.g. a previous prefetch, or a
                // prior zoom-triggered re-render) needs no more work; a page
                // cached only at a lower resolution than now requested (the
                // user has since zoomed in) falls through and re-renders.
                if (documentGeneration.get() != myGeneration) return@withLock
                if (pageIndex !in 0 until renderer.pageCount) return@withLock
                val cachedScale = pageBitmapScale[pageIndex] ?: 0f
                if (_pageBitmaps.value.containsKey(pageIndex) && cachedScale >= clampedMultiplier) {
                    // No actual render needed -- pageRenderState must NOT be
                    // set to Rendering for this call, or it would be left
                    // stuck there forever with nothing to move it back to
                    // Success (this previously happened whenever
                    // onCurrentPageChanged's already-cached fast path ran
                    // straight into a renderPage() call for the same page).
                    return@withLock
                }

                if (isCurrent) {
                    _pageRenderState.update { PageRenderState.Rendering }
                    Log.i(TAG, "DOCUMENT_RENDER_START")
                }

                var page: PdfRenderer.Page? = null
                try {
                    page = renderer.openPage(pageIndex)
                    val pageWidthPts = page.width.toFloat()
                    val pageHeightPts = page.height.toFloat()

                    val targetWidth = (appContext.resources.displayMetrics.widthPixels * clampedMultiplier)
                        .toInt()
                        .coerceAtLeast(1)
                    val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                    var bw = (page.width * scale).toInt().coerceAtLeast(1)
                    var bh = (page.height * scale).toInt().coerceAtLeast(1)

                    var actualMultiplier = clampedMultiplier
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
                        actualMultiplier = clampedMultiplier / 2f
                        Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    }

                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    // Re-check staleness after the (potentially slow) render
                    // completed: don't let a late-finishing render for a
                    // page/document the user has since navigated away from
                    // overwrite what is currently on screen (Document A /
                    // Document B and Page 1 / Page 2 stale-render
                    // protection).
                    if (documentGeneration.get() != myGeneration) {
                        bitmap.recycle()
                        return@withLock
                    }

                    // Replacing a lower-resolution bitmap for this page (a
                    // zoom-triggered re-render) -- recycle the one being
                    // superseded so both copies don't stay resident.
                    // Bitmap.recycle() is safe to call more than once, so no
                    // extra guard is needed against the currentPageBitmap
                    // mirror below also recycling the same object.
                    val replaced = _pageBitmaps.value[pageIndex]
                    _pageBitmaps.update { current -> current + (pageIndex to bitmap) }
                    pageBitmapScale[pageIndex] = actualMultiplier
                    if (replaced != null && replaced !== bitmap && !replaced.isRecycled) {
                        replaced.recycle()
                    }

                    if (pageIndex == _currentPage.value) {
                        _currentPageSize.update { SizeF(pageWidthPts, pageHeightPts) }
                        _currentPageBitmap.update { old ->
                            if (old !== bitmap) old?.takeIf { !it.isRecycled }?.recycle()
                            bitmap
                        }
                        _pageRenderState.update { PageRenderState.Success }
                        Log.i(TAG, "DOCUMENT_RENDER_SUCCESS")
                    }

                    trimPageBitmapWindow()
                } catch (e: CancellationException) {
                    // Expected during rapid navigation/zoom; not a
                    // user-visible failure.
                    throw e
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "DOCUMENT_RENDER_OOM", e)
                    bitmapPool.trim(1.0f)
                    System.gc()
                    if (pageIndex == _currentPage.value) {
                        _pageRenderState.update {
                            PageRenderState.Error("Not enough memory to render this page.")
                        }
                        _viewerState.update {
                            ViewerState.Error(
                                "Not enough memory to render this page. Try closing other apps.",
                                isAccessError = false
                            )
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "DOCUMENT_PERMISSION_FAILURE during render", e)
                    if (pageIndex == _currentPage.value) {
                        _pageRenderState.update {
                            PageRenderState.Error("Access to this document was lost.")
                        }
                        _viewerState.update {
                            ViewerState.Error(
                                "Unable to access this PDF. File access has expired or is no longer available.",
                                isAccessError = true
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "DOCUMENT_RENDER_FAILURE", e)
                    if (pageIndex == _currentPage.value) {
                        _pageRenderState.update {
                            PageRenderState.Error("Failed to render this page.")
                        }
                        _viewerState.update {
                            ViewerState.Error("Failed to render this page.", isAccessError = false)
                        }
                    }
                } finally {
                    page?.close()
                }
            }
        }
        pageRenderJobs.add(job)
        job.invokeOnCompletion { pageRenderJobs.remove(job) }
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

    private suspend fun buildPageLayoutsAndGridsSuspend(renderer: PdfRenderer, myGeneration: Int) {
        gridsMutex.withLock {
            if (documentGeneration.get() != myGeneration) return@withLock
            val layouts = mutableListOf<PageLayout>()
            var baselineWidth = 0f
            var runningTop = 0f
            for (i in 0 until renderer.pageCount) {
                if (documentGeneration.get() != myGeneration) return@withLock
                var page: PdfRenderer.Page? = null
                try {
                    page = renderer.openPage(i)
                    val width = page.width.toFloat()
                    val height = page.height.toFloat()
                    pageGrids[i] = TileGrid(i, width, height, 2.0f)

                    if (i == 0) baselineWidth = width.coerceAtLeast(1f)
                    // Every page is laid out at the same shared document
                    // width (the first page's real width), keeping its
                    // own real aspect ratio for height -- so A4/Letter/
                    // Legal/landscape/portrait pages all stack into one
                    // non-overlapping column instead of being forced
                    // into a single fixed ratio.
                    val docPageHeight = height * (baselineWidth / width.coerceAtLeast(1f))
                    layouts.add(PageLayout(i, width, height, runningTop, docPageHeight))
                    runningTop += docPageHeight + PAGE_GAP_PTS
                } finally {
                    page?.close()
                }
            }
            if (documentGeneration.get() != myGeneration) return@withLock
            _layoutWidth.update { baselineWidth }
            _pageLayouts.update { layouts }
            _documentHeight.update { (runningTop - PAGE_GAP_PTS).coerceAtLeast(0f) }
        }
    }

    private fun scheduleTileRender() {
        // New viewport bounds obsolete only tile work. Cancelling page
        // renders here used to cancel the operation that had set
        // PageRenderState.Rendering, leaving a spinner with no completion
        // path whenever the user scrolled or pinched during loading.
        tileRenderJobs.toList().forEach { it.cancel() }
        tileRenderJobs.clear()

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
                    tileRenderJobs.add(job)
                    job.invokeOnCompletion { tileRenderJobs.remove(job) }
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
