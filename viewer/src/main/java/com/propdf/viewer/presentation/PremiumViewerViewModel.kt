package com.propdf.viewer.presentation

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.repository.PdfViewerRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.result.onError
import com.propdf.core.domain.result.onSuccess
import com.propdf.viewer.cache.PageCacheManager
import com.propdf.viewer.model.Bookmark
import com.propdf.viewer.model.PageHistoryEntry
import com.propdf.viewer.model.PdfTab
import com.propdf.viewer.model.RenderPriority
import com.propdf.viewer.model.SearchResult
import com.propdf.viewer.model.ThumbnailPage
import com.propdf.viewer.model.ViewMode
import com.propdf.viewer.model.ViewerTheme
import com.propdf.viewer.model.ZoomMode
import com.propdf.viewer.render.AsyncPageRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@HiltViewModel
class PremiumViewerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val viewerRepo: PdfViewerRepository,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger
) : ViewModel() {

    companion object {
        private const val TAG = "PremiumViewerVM"
        private const val PRELOAD_RANGE = 3
        private const val THUMBNAIL_NEARBY_RANGE = 6
        private const val THUMBNAIL_VIEWPORT_RANGE = 18
        private const val THUMBNAIL_WIDTH = 200
        private const val MAX_TABS = 5
        private const val MAX_ZOOM = 10.0f
        private const val MIN_ZOOM = 0.1f
        private const val DOUBLE_TAP_ZOOM_IN = 2.5f
        private const val DOUBLE_TAP_ZOOM_OUT = 1.0f
        private const val PAGE_HISTORY_MAX = 50
    }

    private val _uiState = MutableStateFlow(PremiumViewerUiState())
    val uiState: StateFlow<PremiumViewerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ViewerEvent>()
    val events: SharedFlow<ViewerEvent> = _events.asSharedFlow()

    private val lowRamMode = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
        ?.isLowRamDevice == true

    private val cacheManager = PageCacheManager(
        context = context,
        maxMemoryMB = if (lowRamMode) 48 else 128,
        lowRamMode = lowRamMode
    )

    private var renderer: AsyncPageRenderer? = null
    private var currentPfd: ParcelFileDescriptor? = null

    private val tabs = mutableListOf<PdfTab>()
    private var activeTabIndex = 0

    private var preloadJob: Job? = null
    private var thumbnailJob: Job? = null
    private var renderStateJob: Job? = null
    private var searchJob: Job? = null

    private var currentFile: File? = null
    private var totalPages = 0
    private var pageSizes = mutableListOf<Pair<Int, Int>>()

    private val pageHistory = ArrayDeque<PageHistoryEntry>()
    private var historyIndex = -1

    private val bookmarksMutex = Mutex()
    private val bookmarks = mutableListOf<Bookmark>()

    private val recentPagesMutex = Mutex()
    private val recentPages = LinkedHashSet<Int>()

    private var currentSearchQuery = ""

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(5000)
                val cacheSize = cacheManager.getCacheSize()
                val maxSize = cacheManager.getMaxCacheSize()
                if (cacheSize > maxSize * 0.8) {
                    cacheManager.trimToSize((maxSize * 0.6).toInt())
                    logger.d(TAG, "Cache trimmed: $cacheSize -> ${(maxSize * 0.6).toInt()}")
                }
            }
        }
    }

    // ==================== Document Loading ====================

    fun loadDocument(file: File) {
        viewModelScope.launch(dispatchers.io) {
            try {
                _uiState.update { it.copy(isLoading = true, error = null) }
                closeCurrentDocument()

                currentFile = file

                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                currentPfd = pfd

                val openedRenderer = AsyncPageRenderer(
                    context = context,
                    cacheManager = cacheManager
                )
                totalPages = openedRenderer.openDocument(file)
                renderer = openedRenderer
                observeRenderer(openedRenderer)

                pageSizes = openedRenderer.getPageSizes().toMutableList()

                val tab = PdfTab(
                    id = System.currentTimeMillis().toString(),
                    documentUri = file.absolutePath,
                    documentName = file.name,
                    currentPage = 0
                )
                addTab(tab)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        totalPages = totalPages,
                        currentPage = 0,
                        documentName = file.name,
                        tabs = tabs.toList(),
                        pageCount = totalPages,
                        pageSizes = pageSizes
                    )
                }

                preloadPages(0)
                generateThumbnails(centerPage = 0)
                loadBookmarksForDocument(file.absolutePath)

            } catch (e: Exception) {
                logger.e(TAG, "Failed to load document", e)
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to load: ${e.localizedMessage}")
                }
            }
        }
    }

    fun loadDocumentFromUri(uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            try {
                _uiState.update { it.copy(isLoading = true, error = null) }
                closeCurrentDocument()

                val tempFile = copyUriToTempFile(context, uri)
                if (tempFile == null) {
                    _uiState.update { it.copy(isLoading = false, error = "Cannot access file") }
                    return@launch
                }

                loadDocument(tempFile)

            } catch (e: Exception) {
                logger.e(TAG, "Failed to load from URI", e)
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to load: ${e.localizedMessage}")
                }
            }
        }
    }

    // ==================== Document Closing ====================

    fun closeCurrentDocument() {
        renderStateJob?.cancel()
        renderStateJob = null
        preloadJob?.cancel()
        preloadJob = null
        thumbnailJob?.cancel()
        thumbnailJob = null
        searchJob?.cancel()
        searchJob = null

        renderer?.closeDocument()
        renderer = null

        currentPfd?.close()
        currentPfd = null

        cacheManager.clearAll()
        currentFile = null
        totalPages = 0
        pageSizes.clear()
        pageHistory.clear()
        historyIndex = -1

        viewModelScope.launch {
            bookmarksMutex.withLock { bookmarks.clear() }
            recentPagesMutex.withLock { recentPages.clear() }
        }

        _uiState.update {
            it.copy(
                isLoading = false,
                totalPages = 0,
                currentPage = 0,
                documentName = "",
                pages = emptyMap(),
                thumbnails = emptyList(),
                searchResults = emptyList(),
                bookmarks = emptyList(),
                recentPages = emptyList(),
                pageHistory = emptyList()
            )
        }
    }

    // ==================== Page Navigation ====================

    fun goToPage(pageIndex: Int, smooth: Boolean = true, recordHistory: Boolean = true) {
        if (pageIndex < 0 || pageIndex >= totalPages) return

        val oldPage = _uiState.value.currentPage

        if (recordHistory && oldPage != pageIndex) {
            addToHistory(oldPage)
        }

        viewModelScope.launch {
            recentPagesMutex.withLock {
                recentPages.remove(pageIndex)
                recentPages.add(pageIndex)
                while (recentPages.size > 20) {
                    recentPages.remove(recentPages.first())
                }
            }
            _uiState.update { it.copy(recentPages = recentPages.toList().reversed()) }
        }

        _uiState.update {
            it.copy(
                currentPage = pageIndex,
                isRendering = true,
                scrollOffset = 0f
            )
        }

        updateActiveTab { copy(currentPage = pageIndex) }

        if (smooth) {
            viewModelScope.launch {
                _events.emit(ViewerEvent.SmoothScrollToPage(pageIndex))
            }
        }

        preloadPages(pageIndex)
        generateThumbnails(centerPage = pageIndex)

        viewModelScope.launch {
            delay(150)
            _uiState.update { it.copy(isRendering = false) }
        }
    }

    fun nextPage() = goToPage(_uiState.value.currentPage + 1)
    fun prevPage() = goToPage(_uiState.value.currentPage - 1)

    fun goBackInHistory() {
        if (historyIndex < 0) return
        val entry = pageHistory[historyIndex]
        historyIndex--
        goToPage(entry.pageIndex, smooth = true, recordHistory = false)
        _uiState.update {
            it.copy(
                canGoBack = historyIndex >= 0,
                canGoForward = historyIndex < pageHistory.size - 1
            )
        }
    }

    fun goForwardInHistory() {
        if (historyIndex >= pageHistory.size - 1) return
        historyIndex++
        val entry = pageHistory[historyIndex]
        goToPage(entry.pageIndex, smooth = true, recordHistory = false)
        _uiState.update {
            it.copy(
                canGoBack = historyIndex >= 0,
                canGoForward = historyIndex < pageHistory.size - 1
            )
        }
    }

    private fun addToHistory(pageIndex: Int) {
        while (historyIndex < pageHistory.size - 1) {
            pageHistory.removeLast()
        }
        pageHistory.addLast(PageHistoryEntry(pageIndex, System.currentTimeMillis()))
        while (pageHistory.size > PAGE_HISTORY_MAX) {
            pageHistory.removeFirst()
        }
        historyIndex = pageHistory.size - 1
        _uiState.update {
            it.copy(
                canGoBack = historyIndex >= 0,
                canGoForward = false,
                pageHistory = pageHistory.toList()
            )
        }
    }

    fun onPageScrolled(pageIndex: Int, scrollOffset: Float) {
        if (pageIndex != _uiState.value.currentPage) {
            _uiState.update { it.copy(currentPage = pageIndex, scrollOffset = scrollOffset) }
            preloadPages(pageIndex)
            generateThumbnails(centerPage = pageIndex)
            updateActiveTab { copy(currentPage = pageIndex, scrollPosition = scrollOffset.toInt()) }
        }
    }

    fun fastJumpToPage(pageIndex: Int) {
        viewModelScope.launch {
            _events.emit(ViewerEvent.ShowPageScrubber(pageIndex.coerceIn(0, totalPages - 1), totalPages))
        }
    }

    // ==================== Zoom ====================

    fun setZoom(zoom: Float, focalX: Float = 0.5f, focalY: Float = 0.5f) {
        val clampedZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val oldZoom = _uiState.value.zoom

        if (kotlin.math.abs(clampedZoom - oldZoom) > 0.01f) {
            _uiState.update {
                it.copy(
                    zoom = clampedZoom,
                    zoomFocalX = focalX,
                    zoomFocalY = focalY,
                    zoomMode = ZoomMode.CUSTOM
                )
            }
            updateActiveTab { copy(zoomLevel = clampedZoom) }

            if (kotlin.math.abs(clampedZoom - oldZoom) > 0.2f) {
                invalidateVisiblePages()
            }
        }
    }

    fun zoomIn() = setZoom(_uiState.value.zoom * 1.25f)
    fun zoomOut() = setZoom(_uiState.value.zoom / 1.25f)
    fun resetZoom() = setZoom(1.0f)

    fun fitToWidth() {
        _uiState.update { it.copy(zoomMode = ZoomMode.FIT_WIDTH) }
        viewModelScope.launch {
            _events.emit(ViewerEvent.FitToWidth)
        }
    }

    fun fitToPage() {
        _uiState.update { it.copy(zoomMode = ZoomMode.FIT_PAGE) }
        viewModelScope.launch {
            _events.emit(ViewerEvent.FitToPage)
        }
    }

    fun onDoubleTap(x: Float, y: Float) {
        val currentZoom = _uiState.value.zoom
        val targetZoom = if (currentZoom > 1.5f) DOUBLE_TAP_ZOOM_OUT else DOUBLE_TAP_ZOOM_IN
        setZoom(targetZoom, x, y)
        viewModelScope.launch {
            _events.emit(ViewerEvent.AnimateZoom(targetZoom, x, y))
        }
    }

    private fun invalidateVisiblePages() {
        cacheManager.clearPages()
        val currentPage = _uiState.value.currentPage
        renderer?.requestRender(
            AsyncPageRenderer.RenderRequest(
                pageIndex = currentPage,
                width = _uiState.value.screenWidth,
                height = (_uiState.value.screenWidth * 1.414f).toInt(),
                priority = RenderPriority.CRITICAL
            )
        )
    }

    // ==================== View Modes ====================

    fun setViewerMode(mode: ViewMode) {
        _uiState.update { it.copy(viewerMode = mode) }
        updateActiveTab { copy(viewMode = mode.name) }
        viewModelScope.launch {
            _events.emit(ViewerEvent.ModeChanged(mode))
        }
    }

    fun toggleContinuousScroll() {
        val currentMode = _uiState.value.viewerMode
        val newMode = when (currentMode) {
            ViewMode.SINGLE_PAGE_VERTICAL -> ViewMode.CONTINUOUS_VERTICAL
            ViewMode.SINGLE_PAGE_HORIZONTAL -> ViewMode.CONTINUOUS_HORIZONTAL
            ViewMode.CONTINUOUS_VERTICAL -> ViewMode.SINGLE_PAGE_VERTICAL
            ViewMode.CONTINUOUS_HORIZONTAL -> ViewMode.SINGLE_PAGE_HORIZONTAL
            else -> ViewMode.CONTINUOUS_VERTICAL
        }
        setViewerMode(newMode)
    }

    fun toggleHorizontalMode() {
        val currentMode = _uiState.value.viewerMode
        val newMode = when (currentMode) {
            ViewMode.SINGLE_PAGE_VERTICAL -> ViewMode.SINGLE_PAGE_HORIZONTAL
            ViewMode.SINGLE_PAGE_HORIZONTAL -> ViewMode.SINGLE_PAGE_VERTICAL
            ViewMode.CONTINUOUS_VERTICAL -> ViewMode.CONTINUOUS_HORIZONTAL
            ViewMode.CONTINUOUS_HORIZONTAL -> ViewMode.CONTINUOUS_VERTICAL
            else -> ViewMode.SINGLE_PAGE_HORIZONTAL
        }
        setViewerMode(newMode)
    }

    fun toggleTwoPageSpread() {
        val currentMode = _uiState.value.viewerMode
        val newMode = if (currentMode == ViewMode.TWO_PAGE_SPREAD) {
            ViewMode.SINGLE_PAGE_VERTICAL
        } else {
            ViewMode.TWO_PAGE_SPREAD
        }
        setViewerMode(newMode)
    }

    fun toggleReadingMode() {
        val currentMode = _uiState.value.viewerMode
        val newMode = if (currentMode == ViewMode.READING_MODE) {
            ViewMode.CONTINUOUS_VERTICAL
        } else {
            ViewMode.READING_MODE
        }
        setViewerMode(newMode)
    }

    // ==================== Themes ====================

    fun setTheme(theme: ViewerTheme) {
        _uiState.update { it.copy(viewerTheme = theme) }
        renderer?.setTheme(theme)
        updateActiveTab { copy(viewerTheme = theme.name) }
        viewModelScope.launch {
            _events.emit(ViewerEvent.ThemeChanged(theme))
        }
    }

    fun toggleTheme() {
        val newTheme = when (_uiState.value.viewerTheme) {
            ViewerTheme.LIGHT -> ViewerTheme.DARK
            ViewerTheme.DARK -> ViewerTheme.LIGHT
            else -> ViewerTheme.LIGHT
        }
        setTheme(newTheme)
    }

    fun toggleNightMode() {
        val current = _uiState.value.viewerTheme
        setTheme(if (current == ViewerTheme.NIGHT) ViewerTheme.LIGHT else ViewerTheme.NIGHT)
    }

    fun toggleSepiaMode() {
        val current = _uiState.value.viewerTheme
        setTheme(if (current == ViewerTheme.SEPIA) ViewerTheme.LIGHT else ViewerTheme.SEPIA)
    }

    fun toggleHighContrast() {
        val current = _uiState.value.viewerTheme
        setTheme(if (current == ViewerTheme.HIGH_CONTRAST) ViewerTheme.LIGHT else ViewerTheme.HIGH_CONTRAST)
    }

    // ==================== Screen Settings ====================

    fun toggleKeepScreenOn() {
        val newState = !_uiState.value.keepScreenOn
        _uiState.update { it.copy(keepScreenOn = newState) }
        viewModelScope.launch {
            _events.emit(ViewerEvent.KeepScreenOnChanged(newState))
        }
    }

    fun toggleAutoBrightness() {
        val newState = !_uiState.value.autoBrightness
        _uiState.update { it.copy(autoBrightness = newState) }
        viewModelScope.launch {
            _events.emit(ViewerEvent.AutoBrightnessChanged(newState))
        }
    }

    // ==================== Fullscreen ====================

    fun toggleFullscreen() {
        val newState = !_uiState.value.isFullscreen
        _uiState.update { it.copy(isFullscreen = newState) }
        viewModelScope.launch {
            _events.emit(ViewerEvent.FullscreenChanged(newState))
        }
    }

    // ==================== Search ====================

    fun search(query: String) {
        if (query.isBlank()) {
            clearSearch()
            return
        }

        searchJob?.cancel()
        currentSearchQuery = query

        searchJob = viewModelScope.launch(dispatchers.io) {
            try {
                _uiState.update { it.copy(isSearching = true, searchQuery = query) }

                val results = mutableListOf<SearchResult>()
                val file = currentFile ?: return@launch

                for (i in 0 until totalPages) {
                    if (!isActive) break

                    val textResult = viewerRepo.getPageText(file, i)
                    if (textResult is AppResult.Success) {
                        val text = textResult.data
                        val lowerText = text.lowercase()
                        val lowerQuery = query.lowercase()

                        var index = 0
                        val matches = mutableListOf<Int>()
                        while (index < lowerText.length) {
                            val found = lowerText.indexOf(lowerQuery, index)
                            if (found == -1) break
                            matches.add(found)
                            index = found + 1
                        }

                        if (matches.isNotEmpty()) {
                            val firstMatch = matches.first()
                            val snippetStart = (firstMatch - 40).coerceAtLeast(0)
                            val snippetEnd = (firstMatch + query.length + 40).coerceAtMost(text.length)
                            val snippet = text.substring(snippetStart, snippetEnd)

                            results.add(
                                SearchResult(
                                    pageIndex = i,
                                    query = query,
                                    matchCount = matches.size,
                                    textSnippet = snippet,
                                    matchPositions = matches
                                )
                            )
                        }
                    }
                }

                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchResults = results,
                        currentSearchResultIndex = if (results.isNotEmpty()) 0 else -1
                    )
                }

                if (results.isNotEmpty()) {
                    goToPage(results.first().pageIndex)
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Search failed", e)
                _uiState.update { it.copy(isSearching = false, error = "Search failed: ${e.message}") }
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        currentSearchQuery = ""
        _uiState.update {
            it.copy(
                searchResults = emptyList(),
                searchQuery = "",
                isSearching = false,
                currentSearchResultIndex = -1
            )
        }
    }

    fun goToNextSearchResult() {
        val results = _uiState.value.searchResults
        if (results.isEmpty()) return
        val nextIndex = (_uiState.value.currentSearchResultIndex + 1).coerceAtMost(results.size - 1)
        _uiState.update { it.copy(currentSearchResultIndex = nextIndex) }
        goToPage(results[nextIndex].pageIndex)
    }

    fun goToPreviousSearchResult() {
        val results = _uiState.value.searchResults
        if (results.isEmpty()) return
        val prevIndex = (_uiState.value.currentSearchResultIndex - 1).coerceAtLeast(0)
        _uiState.update { it.copy(currentSearchResultIndex = prevIndex) }
        goToPage(results[prevIndex].pageIndex)
    }

    // ==================== Bookmarks ====================

    fun toggleBookmark(pageIndex: Int) {
        viewModelScope.launch {
            bookmarksMutex.withLock {
                val existing = bookmarks.find { it.pageIndex == pageIndex }
                if (existing != null) {
                    bookmarks.remove(existing)
                } else {
                    bookmarks.add(
                        Bookmark(
                            pageIndex = pageIndex,
                            timestamp = System.currentTimeMillis(),
                            label = "Page ${pageIndex + 1}"
                        )
                    )
                }
                bookmarks.sortBy { it.pageIndex }
                _uiState.update { it.copy(bookmarks = bookmarks.toList()) }
            }
            saveBookmarksForDocument()
        }
    }

    fun isPageBookmarked(pageIndex: Int): Boolean {
        return bookmarks.any { it.pageIndex == pageIndex }
    }

    fun goToBookmark(bookmark: Bookmark) {
        goToPage(bookmark.pageIndex)
    }

    fun deleteBookmark(pageIndex: Int) {
        viewModelScope.launch {
            bookmarksMutex.withLock {
                bookmarks.removeAll { it.pageIndex == pageIndex }
                _uiState.update { it.copy(bookmarks = bookmarks.toList()) }
            }
            saveBookmarksForDocument()
        }
    }

    private suspend fun loadBookmarksForDocument(documentPath: String) {
        // Load from persistent storage
    }

    private suspend fun saveBookmarksForDocument() {
        // Save to persistent storage
    }

    // ==================== Thumbnails ====================

    private fun generateThumbnails(centerPage: Int) {
        thumbnailJob?.cancel()

        thumbnailJob = viewModelScope.launch(dispatchers.io) {
            try {
                val range = (centerPage - THUMBNAIL_VIEWPORT_RANGE)..(centerPage + THUMBNAIL_VIEWPORT_RANGE)
                val validRange = range.coerceIn(0, totalPages - 1)

                val thumbnails = mutableListOf<ThumbnailPage>()
                for (i in validRange) {
                    if (!isActive) break

                    val bitmap = renderer?.renderThumbnail(i, THUMBNAIL_WIDTH)
                    if (bitmap != null) {
                        thumbnails.add(ThumbnailPage(i, bitmap))
                    }
                }

                _uiState.update { it.copy(thumbnails = thumbnails) }

                val nearbyRange = (centerPage - THUMBNAIL_NEARBY_RANGE)..(centerPage + THUMBNAIL_NEARBY_RANGE)
                for (i in nearbyRange.coerceIn(0, totalPages - 1)) {
                    if (!isActive) break
                    if (thumbnails.none { it.pageIndex == i }) {
                        renderer?.renderThumbnail(i, THUMBNAIL_WIDTH)
                    }
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Thumbnail generation failed", e)
            }
        }
    }

    // ==================== Preloading ====================

    private fun preloadPages(anchorPage: Int) {
        preloadJob?.cancel()

        preloadJob = viewModelScope.launch(dispatchers.io) {
            try {
                val range = (anchorPage - PRELOAD_RANGE)..(anchorPage + PRELOAD_RANGE)
                for (i in range.coerceIn(0, totalPages - 1)) {
                    if (!isActive) break
                    if (i == anchorPage) continue

                    renderer?.requestRender(
                        AsyncPageRenderer.RenderRequest(
                            pageIndex = i,
                            width = _uiState.value.screenWidth,
                            height = (_uiState.value.screenWidth * 1.414f).toInt(),
                            priority = RenderPriority.NORMAL
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    // ==================== Rotation ====================

    fun onRotationChanged(orientation: Int) {
        val isLandscape = orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        _uiState.update { it.copy(isLandscape = isLandscape) }

        if (isLandscape && _uiState.value.viewerMode == ViewMode.SINGLE_PAGE_VERTICAL) {
            setViewerMode(ViewMode.TWO_PAGE_SPREAD)
        }
    }

    // ==================== Tab Management ====================

    private fun addTab(tab: PdfTab) {
        if (tabs.size >= MAX_TABS) {
            tabs.removeAt(0)
        }
        tabs.add(tab)
        activeTabIndex = tabs.size - 1
    }

    fun switchTab(index: Int) {
        if (index in tabs.indices) {
            activeTabIndex = index
            val tab = tabs[index]
            _uiState.update {
                it.copy(
                    currentPage = tab.currentPage,
                    zoom = tab.zoomLevel,
                    viewerMode = ViewMode.valueOf(tab.viewMode)
                )
            }
        }
    }

    fun closeTab(index: Int) {
        if (index in tabs.indices && tabs.size > 1) {
            tabs.removeAt(index)
            if (activeTabIndex >= tabs.size) activeTabIndex = tabs.size - 1
            switchTab(activeTabIndex)
        }
    }

    private fun updateActiveTab(block: PdfTab.() -> PdfTab) {
        if (activeTabIndex in tabs.indices) {
            tabs[activeTabIndex] = tabs[activeTabIndex].block()
            _uiState.update { it.copy(tabs = tabs.toList()) }
        }
    }

    // ==================== Screen Dimensions ====================

    fun setScreenDimensions(width: Int, height: Int) {
        _uiState.update { it.copy(screenWidth = width, screenHeight = height) }
    }

    // ==================== Renderer Observation ====================

    private fun observeRenderer(renderer: AsyncPageRenderer) {
        renderStateJob?.cancel()
        renderStateJob = viewModelScope.launch {
            renderer.renderState.collect { state ->
                when (state) {
                    is AsyncPageRenderer.RenderState.Complete -> {
                        _uiState.update { currentState ->
                            val pages = currentState.pages.toMutableMap()
                            pages[state.pageIndex] = state.bitmap
                            currentState.copy(pages = pages)
                        }
                    }
                    is AsyncPageRenderer.RenderState.Error -> {
                        logger.e(TAG, "Render error page ${state.pageIndex}", state.throwable)
                    }
                    else -> {}
                }
            }
        }
    }

    // ==================== Utility ====================

    private fun IntRange.coerceIn(min: Int, max: Int): IntRange {
        return kotlin.math.max(first, min)..kotlin.math.min(last, max)
    }

    private suspend fun copyUriToTempFile(context: Context, uri: Uri): File? = withContext(dispatchers.io) {
        try {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            } ?: "document_${System.currentTimeMillis()}.pdf"

            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dest = File(context.cacheDir, safeName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null

            if (dest.exists() && dest.length() > 0) dest else null
        } catch (e: Exception) {
            logger.e(TAG, "Failed to copy URI to temp file", e)
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeCurrentDocument()
    }

    // ==================== Events ====================

    sealed class ViewerEvent {
        data class SmoothScrollToPage(val pageIndex: Int) : ViewerEvent()
        data class ShowPageScrubber(val currentPage: Int, val totalPages: Int) : ViewerEvent()
        data class ModeChanged(val mode: ViewMode) : ViewerEvent()
        data class ThemeChanged(val theme: ViewerTheme) : ViewerEvent()
        object FitToWidth : ViewerEvent()
        object FitToPage : ViewerEvent()
        data class AnimateZoom(val zoom: Float, val focalX: Float, val focalY: Float) : ViewerEvent()
        data class FullscreenChanged(val isFullscreen: Boolean) : ViewerEvent()
        data class KeepScreenOnChanged(val keepOn: Boolean) : ViewerEvent()
        data class AutoBrightnessChanged(val auto: Boolean) : ViewerEvent()
    }
}

// ==================== UI State ====================

data class PremiumViewerUiState(
    val isLoading: Boolean = false,
    val isRendering: Boolean = false,
    val isSearching: Boolean = false,
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    val scrollOffset: Float = 0f,
    val zoom: Float = 1.0f,
    val zoomFocalX: Float = 0.5f,
    val zoomFocalY: Float = 0.5f,
    val zoomMode: ZoomMode = ZoomMode.FIT_PAGE,
    val viewerMode: ViewMode = ViewMode.CONTINUOUS_VERTICAL,
    val viewerTheme: ViewerTheme = ViewerTheme.LIGHT,
    val isDark: Boolean = false,
    val isFullscreen: Boolean = false,
    val isLandscape: Boolean = false,
    val screenWidth: Int = 1080,
    val screenHeight: Int = 1920,
    val pages: Map<Int, Bitmap> = emptyMap(),
    val thumbnails: List<ThumbnailPage> = emptyList(),
    val searchResults: List<SearchResult> = emptyList(),
    val searchQuery: String = "",
    val currentSearchResultIndex: Int = -1,
    val bookmarks: List<Bookmark> = emptyList(),
    val recentPages: List<Int> = emptyList(),
    val pageHistory: List<PageHistoryEntry> = emptyList(),
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val keepScreenOn: Boolean = false,
    val autoBrightness: Boolean = false,
    val documentName: String = "",
    val fileName: String = "",
    val tabs: List<PdfTab> = emptyList(),
    val pageCount: Int = 0,
    val pageSizes: List<Pair<Int, Int>> = emptyList(),
    val error: String? = null
)
