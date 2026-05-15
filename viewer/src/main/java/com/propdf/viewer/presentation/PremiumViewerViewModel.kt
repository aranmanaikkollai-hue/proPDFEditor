package com.propdf.viewer.presentation

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.repository.PdfViewerRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.result.onError
import com.propdf.core.domain.result.onSuccess
import com.propdf.viewer.cache.PageCacheManager
import com.propdf.viewer.model.PdfTab
import com.propdf.viewer.model.RenderPriority
import com.propdf.viewer.model.ViewMode
import com.propdf.viewer.model.ViewerTheme
import com.propdf.viewer.model.ZoomMode
import com.propdf.viewer.render.AsyncPageRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Premium PDF Viewer ViewModel with all advanced features:
 * - Multi-tab viewing with state restoration
 * - Multiple viewing modes (continuous, horizontal, two-page, reading)
 * - Theme management (light, dark, night, sepia, high-contrast)
 * - Intelligent preloading with priority queue
 * - Zoom state management with focal point tracking
 * - Thumbnail generation and caching
 * - Search across all pages
 * - Page scrubber and fast jump
 */
@HiltViewModel
class PremiumViewerViewModel @Inject constructor(
    private val viewerRepo: PdfViewerRepository,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger
) : ViewModel() {

    companion object {
        private const val TAG = "PremiumViewerVM"
        private const val PRELOAD_RANGE = 3
        private const val THUMBNAIL_WIDTH = 200
        private const val MAX_TABS = 5
        private const val MAX_ZOOM = 10.0f
        private const val MIN_ZOOM = 0.1f
    }

    // Core state
    private val _uiState = MutableStateFlow(PremiumViewerUiState())
    val uiState: StateFlow<PremiumViewerUiState> = _uiState.asStateFlow()

    // One-shot events
    private val _events = MutableSharedFlow<ViewerEvent>()
    val events: SharedFlow<ViewerEvent> = _events.asSharedFlow()

    // Cache and renderer
    private val cacheManager = PageCacheManager(maxMemoryMB = 128)
    private var renderer: AsyncPageRenderer? = null

    // Tab management
    private val tabs = mutableListOf<PdfTab>()
    private var activeTabIndex = 0

    // Preloading jobs
    private var preloadJob: Job? = null
    private var thumbnailJob: Job? = null

    // Current document
    private var currentFile: File? = null
    private var totalPages = 0

    init {
        // Monitor cache pressure every 5 seconds
        viewModelScope.launch {
            while (true) {
                delay(5000)
                val cacheSize = cacheManager.getCacheSize()
                val maxSize = cacheManager.getMaxCacheSize()
                if (cacheSize > maxSize * 0.8) {
                    cacheManager.trimToSize((maxSize * 0.6).toInt())
                    logger.d(TAG, "Cache trimmed: ${cacheSize} -> ${(maxSize * 0.6).toInt()}")
                }
            }
        }
    }

    // ==================== DOCUMENT LOADING ====================

    fun loadDocument(file: File) {
        viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(isLoading = true, error = null) }

            closeCurrentDocument()
            currentFile = file

            // Initialize renderer
            renderer = AsyncPageRenderer(
                context = TODO(), // Injected via factory or application context
                cacheManager = cacheManager
            ).apply {
                openDocument(file)
                totalPages = getPageCount()
            }

            // Create new tab
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
                    tabs = tabs.toList()
                )
            }

            // Start preloading
            preloadPages(0)
            generateThumbnails()
        }
    }

    fun closeCurrentDocument() {
        renderer?.release()
        renderer = null
        cacheManager.clearAll()
        currentFile = null
        totalPages = 0
    }

    // ==================== PAGE NAVIGATION ====================

    fun goToPage(pageIndex: Int, smooth: Boolean = true) {
        if (pageIndex < 0 || pageIndex >= totalPages) return

        _uiState.update {
            it.copy(
                currentPage = pageIndex,
                isRendering = true
            )
        }

        updateActiveTab { copy(currentPage = pageIndex) }

        // Emit scroll event for smooth animation
        if (smooth) {
            viewModelScope.launch {
                _events.emit(ViewerEvent.SmoothScrollToPage(pageIndex))
            }
        }

        // Preload nearby pages
        preloadPages(pageIndex)

        viewModelScope.launch {
            delay(150)
            _uiState.update { it.copy(isRendering = false) }
        }
    }

    fun nextPage() = goToPage(_uiState.value.currentPage + 1)
    fun prevPage() = goToPage(_uiState.value.currentPage - 1)

    fun fastJumpToPage(pageIndex: Int) {
        viewModelScope.launch {
            _events.emit(ViewerEvent.ShowPageScrubber(pageIndex.coerceIn(0, totalPages - 1), totalPages))
        }
    }

    fun onPageScrolled(pageIndex: Int, scrollOffset: Float) {
        if (pageIndex != _uiState.value.currentPage) {
            _uiState.update { it.copy(currentPage = pageIndex, scrollOffset = scrollOffset) }
            preloadPages(pageIndex)
            updateActiveTab { copy(currentPage = pageIndex, scrollPosition = scrollOffset.toInt()) }
        }
    }

    // ==================== ZOOM ====================

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

            // Invalidate cache and re-render at new zoom if significant change
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

    // ==================== VIEWING MODES ====================

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

    // ==================== THEMES ====================

    fun setTheme(theme: ViewerTheme) {
        _uiState.update { it.copy(theme = theme) }
        renderer?.setTheme(theme)
        updateActiveTab { copy(theme = theme.name) }
        viewModelScope.launch {
            _events.emit(ViewerEvent.ThemeChanged(theme))
        }
    }

    fun toggleTheme() {
        val themes = ViewerTheme.values()
        val currentIndex = themes.indexOf(_uiState.value.theme)
        val nextTheme = themes[(currentIndex + 1) % themes.size]
        setTheme(nextTheme)
    }

    fun toggleNightMode() {
        val current = _uiState.value.theme
        setTheme(if (current == ViewerTheme.NIGHT) ViewerTheme.LIGHT else ViewerTheme.NIGHT)
    }

    fun toggleDarkMode() {
        val current = _uiState.value.theme
        setTheme(if (current == ViewerTheme.DARK) ViewerTheme.LIGHT else ViewerTheme.DARK)
    }

    fun toggleSepiaMode() {
        val current = _uiState.value.theme
        setTheme(if (current == ViewerTheme.SEPIA) ViewerTheme.LIGHT else ViewerTheme.SEPIA)
    }

    // ==================== TAB MANAGEMENT ====================

    fun addTab(tab: PdfTab) {
        if (tabs.size >= MAX_TABS) {
            tabs.removeAt(0)
        }
        tabs.add(tab)
        activeTabIndex = tabs.size - 1
        _uiState.update { it.copy(tabs = tabs.toList(), activeTabIndex = activeTabIndex) }
    }

    fun switchTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        activeTabIndex = index
        val tab = tabs[index]
        _uiState.update {
            it.copy(
                activeTabIndex = index,
                currentPage = tab.currentPage,
                zoom = tab.zoomLevel
            )
        }
    }

    fun closeTab(index: Int) {
        if (tabs.size <= 1) return
        tabs.removeAt(index)
        if (activeTabIndex >= tabs.size) activeTabIndex = tabs.size - 1
        _uiState.update { it.copy(tabs = tabs.toList(), activeTabIndex = activeTabIndex) }
    }

    fun openNewTab(file: File) {
        loadDocument(file)
    }

    private fun updateActiveTab(transform: PdfTab.() -> PdfTab) {
        if (activeTabIndex in tabs.indices) {
            tabs[activeTabIndex] = tabs[activeTabIndex].transform()
            _uiState.update { it.copy(tabs = tabs.toList()) }
        }
    }

    // ==================== PRELOADING ====================

    private fun preloadPages(centerPage: Int) {
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch(dispatchers.io) {
            val range = -PRELOAD_RANGE..PRELOAD_RANGE
            for (offset in range) {
                val pageIndex = centerPage + offset
                if (pageIndex in 0 until totalPages && offset != 0) {
                    if (cacheManager.getPage(pageIndex) == null &&
                        !cacheManager.isInPreloadQueue(pageIndex)
                    ) {
                        cacheManager.addToPreloadQueue(pageIndex)
                        renderer?.requestRender(
                            AsyncPageRenderer.RenderRequest(
                                pageIndex = pageIndex,
                                width = _uiState.value.screenWidth,
                                height = (_uiState.value.screenWidth * 1.414f).toInt(),
                                priority = when {
                                    offset == 0 -> RenderPriority.CRITICAL
                                    kotlin.math.abs(offset) <= 1 -> RenderPriority.HIGH
                                    else -> RenderPriority.LOW
                                }
                            )
                        )
                    }
                }
            }
        }
    }

    private fun generateThumbnails() {
        thumbnailJob?.cancel()
        thumbnailJob = viewModelScope.launch(dispatchers.io) {
            for (i in 0 until totalPages) {
                if (!isActive) break
                if (cacheManager.getThumbnail(i) == null) {
                    renderer?.requestRender(
                        AsyncPageRenderer.RenderRequest(
                            pageIndex = i,
                            width = THUMBNAIL_WIDTH,
                            height = (THUMBNAIL_WIDTH * 1.414f).toInt(),
                            priority = RenderPriority.LOW,
                            isThumbnail = true
                        )
                    )
                }
            }
        }
    }

    // ==================== SEARCH ====================

    fun search(query: String) {
        val file = currentFile ?: return
        if (query.isBlank()) {
            clearSearch()
            return
        }

        viewModelScope.launch(dispatchers.io) {
            _uiState.update {
                it.copy(isSearching = true, searchQuery = query, searchResults = emptyList())
            }
            val results = mutableListOf<SearchResult>()

            for (i in 0 until totalPages) {
                val textResult = viewerRepo.getPageText(file, i)
                if (textResult is AppResult.Success) {
                    val text = textResult.data
                    var index = text.indexOf(query, ignoreCase = true)
                    while (index >= 0) {
                        results.add(SearchResult(pageIndex = i, charIndex = index, query = query))
                        index = text.indexOf(query, index + 1, ignoreCase = true)
                    }
                }
            }

            _uiState.update {
                it.copy(
                    isSearching = false,
                    searchResults = results,
                    currentSearchIndex = if (results.isNotEmpty()) 0 else -1
                )
            }

            if (results.isNotEmpty()) {
                goToPage(results[0].pageIndex)
            }
        }
    }

    fun nextSearchResult() {
        val results = _uiState.value.searchResults
        val currentIndex = _uiState.value.currentSearchIndex
        if (results.isNotEmpty() && currentIndex < results.size - 1) {
            val nextIndex = currentIndex + 1
            _uiState.update { it.copy(currentSearchIndex = nextIndex) }
            goToPage(results[nextIndex].pageIndex)
        }
    }

    fun prevSearchResult() {
        val results = _uiState.value.searchResults
        val currentIndex = _uiState.value.currentSearchIndex
        if (results.isNotEmpty() && currentIndex > 0) {
            val prevIndex = currentIndex - 1
            _uiState.update { it.copy(currentSearchIndex = prevIndex) }
            goToPage(results[prevIndex].pageIndex)
        }
    }

    fun clearSearch() {
        _uiState.update {
            it.copy(
                searchResults = emptyList(),
                searchQuery = "",
                currentSearchIndex = -1
            )
        }
    }

    // ==================== UI CONTROLS ====================

    fun toggleToolbar() {
        _uiState.update { it.copy(isToolbarVisible = !it.isToolbarVisible) }
    }

    fun toggleThumbnailSidebar() {
        _uiState.update { it.copy(isThumbnailSidebarVisible = !it.isThumbnailSidebarVisible) }
    }

    fun toggleBottomSheet() {
        _uiState.update { it.copy(isBottomSheetVisible = !it.isBottomSheetVisible) }
    }

    fun showPagePreview(pageIndex: Int) {
        viewModelScope.launch {
            _events.emit(ViewerEvent.ShowPagePreview(pageIndex))
        }
    }

    fun setScreenDimensions(width: Int, height: Int) {
        _uiState.update { it.copy(screenWidth = width, screenHeight = height) }
    }

    fun onScrollPositionChanged(position: Int) {
        _uiState.update { it.copy(scrollPosition = position) }
        updateActiveTab { copy(scrollPosition = position) }
    }

    // ==================== CLEANUP ====================

    override fun onCleared() {
        super.onCleared()
        preloadJob?.cancel()
        thumbnailJob?.cancel()
        closeCurrentDocument()
    }

    // ==================== DATA CLASSES ====================

    data class PremiumViewerUiState(
        val isLoading: Boolean = false,
        val isRendering: Boolean = false,
        val isSearching: Boolean = false,
        val totalPages: Int = 0,
        val currentPage: Int = 0,
        val zoom: Float = 1.0f,
        val zoomFocalX: Float = 0.5f,
        val zoomFocalY: Float = 0.5f,
        val zoomMode: ZoomMode = ZoomMode.FIT_WIDTH,
        val viewerMode: ViewMode = ViewMode.CONTINUOUS_VERTICAL,
        val theme: ViewerTheme = ViewerTheme.LIGHT,
        val scrollPosition: Int = 0,
        val scrollOffset: Float = 0f,
        val screenWidth: Int = 1080,
        val screenHeight: Int = 1920,
        val documentName: String = "",
        val tabs: List<PdfTab> = emptyList(),
        val activeTabIndex: Int = 0,
        val isToolbarVisible: Boolean = true,
        val isThumbnailSidebarVisible: Boolean = false,
        val isBottomSheetVisible: Boolean = false,
        val searchResults: List<SearchResult> = emptyList(),
        val searchQuery: String = "",
        val currentSearchIndex: Int = -1,
        val error: String? = null
    )

    data class SearchResult(
        val pageIndex: Int,
        val charIndex: Int,
        val query: String
    )

    sealed class ViewerEvent {
        data class SmoothScrollToPage(val pageIndex: Int) : ViewerEvent()
        data class ShowPageScrubber(val currentPage: Int, val totalPages: Int) : ViewerEvent()
        data class ShowPagePreview(val pageIndex: Int) : ViewerEvent()
        data class ModeChanged(val mode: ViewMode) : ViewerEvent()
        data class ThemeChanged(val theme: ViewerTheme) : ViewerEvent()
        object FitToWidth : ViewerEvent()
        object FitToPage : ViewerEvent()
        data class ShowError(val message: String) : ViewerEvent()
    }
}
