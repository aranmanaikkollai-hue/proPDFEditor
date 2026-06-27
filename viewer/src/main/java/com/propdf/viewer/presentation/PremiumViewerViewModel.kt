package com.propdf.viewer.presentation

import android.app.ActivityManager
import android.content.Context
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
import com.propdf.viewer.rendering.AsyncPageRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
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

    private val tabs = mutableListOf<PdfTab>()
    private var activeTabIndex = 0

    private var preloadJob: Job? = null
    private var thumbnailJob: Job? = null
    private var renderStateJob: Job? = null

    private var currentFile: File? = null
    private var totalPages = 0

    init {
        viewModelScope.launch {
            while (true) {
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

    fun loadDocument(file: File) {
        viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(isLoading = true, error = null) }

            closeCurrentDocument()
            currentFile = file

            val openedRenderer = AsyncPageRenderer(
                context = context,
                cacheManager = cacheManager
            )
            totalPages = openedRenderer.openDocument(file)
            renderer = openedRenderer
            observeRenderer(openedRenderer)

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

            preloadPages(0)
            generateThumbnails(centerPage = 0)
        }
    }

    fun closeCurrentDocument() {
        renderStateJob?.cancel()
        renderStateJob = null
        renderer?.release()
        renderer = null
        cacheManager.clearAll()
        currentFile = null
        totalPages = 0
    }

    fun goToPage(pageIndex: Int, smooth: Boolean = true) {
        if (pageIndex < 0 || pageIndex >= totalPages) return

        _uiState.update {
            it.copy(
                currentPage = pageIndex,
                isRendering = true
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

    fun fastJumpToPage(pageIndex: Int) {
        viewModelScope.launch {
            _events.emit(ViewerEvent.ShowPageScrubber(pageIndex.coerceIn(0, totalPages - 1), totalPages))
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

    fun setTheme(theme: ViewerTheme) {
        _uiState.update { it.copy(theme = theme) }
        renderer?.setTheme(theme)
        updateActiveTab { copy(theme = theme.name) }
        viewModelScope.launch {
            _events.emit(ViewerEvent.ThemeChanged(theme))
        }
    }

    fun toggleTheme() {
        val themes = ViewerTheme.entries
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

    private fun preloadPages(centerPage: Int) {
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch(dispatchers.io) {
            val range = -PRELOAD_RANGE..PRELOAD_RANGE
            for (offset in range) {
                val pageIndex = centerPage + offset
                if (pageIndex in 0 until totalPages) {
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

    private fun generateThumbnails(
        centerPage: Int = _uiState.value.currentPage,
        radius: Int = THUMBNAIL_NEARBY_RANGE
    ) {
        thumbnailJob?.cancel()
        thumbnailJob = viewModelScope.launch(dispatchers.io) {
            val start = (centerPage - radius).coerceAtLeast(0)
            val end = (centerPage + radius).coerceAtMost(totalPages - 1)
            for (i in start..end) {
                if (!isActive) break
                val cached = cacheManager.getThumbnail(i)
                if (cached != null) {
                    _events.emit(ViewerEvent.ThumbnailRendered(i, cached))
                } else {
                    renderer?.requestRender(
                        AsyncPageRenderer.RenderRequest(
                            pageIndex = i,
                            width = THUMBNAIL_WIDTH,
                            height = (THUMBNAIL_WIDTH * 1.414f).toInt(),
                            priority = if (kotlin.math.abs(i - centerPage) <= 2) RenderPriority.NORMAL else RenderPriority.LOW,
                            isThumbnail = true
                        )
                    )
                }
            }
        }
    }

    private fun observeRenderer(renderer: AsyncPageRenderer) {
        renderStateJob?.cancel()
        renderStateJob = viewModelScope.launch {
            renderer.renderState.collectLatest { state ->
                when (state) {
                    is AsyncPageRenderer.RenderState.Complete -> {
                        cacheManager.removeFromPreloadQueue(state.pageIndex)
                        _uiState.update { it.copy(isRendering = false) }
                        if (state.isThumbnail) {
                            _events.emit(ViewerEvent.ThumbnailRendered(state.pageIndex, state.bitmap))
                        } else {
                            _events.emit(ViewerEvent.PageRendered(state.pageIndex, state.bitmap))
                        }
                    }
                    is AsyncPageRenderer.RenderState.Error -> {
                        cacheManager.removeFromPreloadQueue(state.pageIndex)
                        _uiState.update { it.copy(isRendering = false, error = state.throwable.message) }
                        _events.emit(ViewerEvent.ShowError(state.throwable.message ?: "Unable to render page"))
                    }
                    AsyncPageRenderer.RenderState.Idle -> Unit
                    is AsyncPageRenderer.RenderState.Rendering -> Unit
                }
            }
        }
    }

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

    fun toggleToolbar() {
        _uiState.update { it.copy(isToolbarVisible = !it.isToolbarVisible) }
    }

    fun toggleThumbnailSidebar() {
        val willShow = !_uiState.value.isThumbnailSidebarVisible
        _uiState.update { it.copy(isThumbnailSidebarVisible = willShow) }
        if (willShow) {
            generateThumbnails(centerPage = _uiState.value.currentPage, radius = THUMBNAIL_VIEWPORT_RANGE)
        }
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

    override fun onCleared() {
        super.onCleared()
        preloadJob?.cancel()
        thumbnailJob?.cancel()
        closeCurrentDocument()
    }

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
        data class PageRendered(val pageIndex: Int, val bitmap: Bitmap) : ViewerEvent()
        data class ThumbnailRendered(val pageIndex: Int, val bitmap: Bitmap) : ViewerEvent()
    }
}
