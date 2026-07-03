package com.propdf.editor.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

private const val MAX_HISTORY = 50
private const val PAGE_BITMAP_CACHE_BYTES = 96 * 1024 * 1024 // 96MB of full-res page bitmaps
private const val THUMBNAIL_CACHE_BYTES = 24 * 1024 * 1024 // 24MB of thumbnails
private const val THUMBNAIL_WIDTH_PX = 140

@HiltViewModel
class PdfViewerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private var pdfRenderer: PdfRenderer? = null
    private var documentFile: File? = null
    private var documentKey: String = ""
    private val renderMutex = Mutex()

    private val pageBitmapCache = PdfBitmapCache(PAGE_BITMAP_CACHE_BYTES)
    private val thumbnailCache = PdfBitmapCache(THUMBNAIL_CACHE_BYTES)
    private val searchEngine = PdfSearchEngine()
    private val preferences = PdfViewerPreferences(appContext)

    // ---- Document state ----
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _fileName = MutableStateFlow("Document")
    val fileName: StateFlow<String> = _fileName.asStateFlow()

    private val _pages = MutableStateFlow<List<PdfPageInfo>>(emptyList())
    val pages: StateFlow<List<PdfPageInfo>> = _pages.asStateFlow()

    private val _pageCount = MutableStateFlow(0)
    val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    /** Absolute path to the resolved local copy of the document, needed by annotation export/flatten/burn. */
    private val _documentFilePath = MutableStateFlow("")
    val documentFilePath: StateFlow<String> = _documentFilePath.asStateFlow()

    // ---- Layout / display state ----
    private val _viewMode = MutableStateFlow(preferences.getViewMode())
    val viewMode: StateFlow<PdfViewMode> = _viewMode.asStateFlow()

    private val _colorMode = MutableStateFlow(preferences.getColorMode())
    val colorMode: StateFlow<PdfColorMode> = _colorMode.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(preferences.getKeepScreenOn())
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _autoBrightness = MutableStateFlow(preferences.getAutoBrightness())
    val autoBrightness: StateFlow<Boolean> = _autoBrightness.asStateFlow()

    private val _manualBrightness = MutableStateFlow(preferences.getManualBrightness())
    val manualBrightness: StateFlow<Float> = _manualBrightness.asStateFlow()

    private val _zoom = MutableStateFlow(1f)
    val zoom: StateFlow<Float> = _zoom.asStateFlow()

    private val _rotation = MutableStateFlow(0)
    val rotation: StateFlow<Int> = _rotation.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()

    private val _isThumbnailSidebarOpen = MutableStateFlow(false)
    val isThumbnailSidebarOpen: StateFlow<Boolean> = _isThumbnailSidebarOpen.asStateFlow()

    private val _isBookmarksPanelOpen = MutableStateFlow(false)
    val isBookmarksPanelOpen: StateFlow<Boolean> = _isBookmarksPanelOpen.asStateFlow()

    // ---- Bookmarks / history ----
    private val _bookmarks = MutableStateFlow<Set<Int>>(emptySet())
    val bookmarks: StateFlow<Set<Int>> = _bookmarks.asStateFlow()

    private val _history = MutableStateFlow<List<PdfHistoryEntry>>(emptyList())
    val history: StateFlow<List<PdfHistoryEntry>> = _history.asStateFlow()

    /** One-shot events telling the UI to actually scroll/page to a target index. */
    private val _navigateToPage = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val navigateToPage: SharedFlow<Int> = _navigateToPage.asSharedFlow()

    // ---- Search ----
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchResults = MutableStateFlow<List<PdfSearchMatch>>(emptyList())
    val searchResults: StateFlow<List<PdfSearchMatch>> = _searchResults.asStateFlow()

    private val _currentSearchMatchIndex = MutableStateFlow(-1)
    val currentSearchMatchIndex: StateFlow<Int> = _currentSearchMatchIndex.asStateFlow()

    // ==================== Document lifecycle ====================

    fun openDocument(uri: String) {
        if (documentKey == uri && pdfRenderer != null) {
            _isLoading.value = false
            return // already open, e.g. re-composition after rotation
        }
        documentKey = uri
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            withContext(Dispatchers.IO) {
                try {
                    closeInternal()
                    val parsed = Uri.parse(uri)
                    _fileName.value = resolveDisplayName(parsed)
                    val file = resolvePdfToLocalFile(parsed)
                        ?: throw Exception("Cannot open file: unable to obtain read access")
                    documentFile = file
                    _documentFilePath.value = file.absolutePath

                    val renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                    pdfRenderer = renderer
                    _pageCount.value = renderer.pageCount

                    val pageInfos = (0 until renderer.pageCount).map { i ->
                        val page = renderer.openPage(i)
                        val info = PdfPageInfo(i, page.width.toFloat(), page.height.toFloat())
                        page.close()
                        info
                    }
                    _pages.value = pageInfos

                    // Restore per-document state (zoom memory, last page, bookmarks, rotation)
                    _zoom.value = preferences.getZoom(uri)
                    _rotation.value = preferences.getRotation(uri)
                    _bookmarks.value = preferences.getBookmarks(uri)
                    val lastPage = preferences.getLastPage(uri).coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                    _currentPage.value = lastPage

                    _isLoading.value = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    _errorMessage.value = "Cannot open PDF: permission denied. Please re-select the file using the file picker."
                    _isLoading.value = false
                } catch (e: OutOfMemoryError) {
                    _errorMessage.value = "Cannot open PDF: file is too large for available memory."
                    _isLoading.value = false
                } catch (e: Exception) {
                    _errorMessage.value = "Cannot open PDF: ${e.localizedMessage ?: e.message}"
                    _isLoading.value = false
                }
            }
        }
    }

    private fun closeInternal() {
        pdfRenderer?.close()
        pdfRenderer = null
        pageBitmapCache.clear()
        thumbnailCache.clear()
    }

    override fun onCleared() {
        super.onCleared()
        if (documentKey.isNotBlank()) {
            preferences.setZoom(documentKey, _zoom.value)
            preferences.setLastPage(documentKey, _currentPage.value)
            preferences.setRotation(documentKey, _rotation.value)
        }
        closeInternal()
    }

    // ==================== Rendering ====================

    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? {
        val renderer = pdfRenderer ?: return null
        if (pageIndex !in 0 until renderer.pageCount || targetWidthPx <= 0) return null
        val cacheKey = PdfBitmapCache.key(pageIndex, targetWidthPx)
        pageBitmapCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                renderMutex.withLock {
                    pageBitmapCache.get(cacheKey)?.let { return@withLock it }
                    val page = renderer.openPage(pageIndex)
                    val bitmap = try {
                        val ratio = targetWidthPx.toFloat() / page.width
                        val heightPx = (page.height * ratio).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(targetWidthPx, heightPx, Bitmap.Config.ARGB_8888).also { bmp ->
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    } finally {
                        page.close()
                    }
                    pageBitmapCache.put(cacheKey, bitmap)
                    bitmap
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun renderThumbnail(pageIndex: Int): Bitmap? {
        val renderer = pdfRenderer ?: return null
        if (pageIndex !in 0 until renderer.pageCount) return null
        val cacheKey = pageIndex.toString()
        thumbnailCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                renderMutex.withLock {
                    thumbnailCache.get(cacheKey)?.let { return@withLock it }
                    val page = renderer.openPage(pageIndex)
                    val bitmap = try {
                        val ratio = THUMBNAIL_WIDTH_PX.toFloat() / page.width
                        val heightPx = (page.height * ratio).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(THUMBNAIL_WIDTH_PX, heightPx, Bitmap.Config.ARGB_8888).also { bmp ->
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    } finally {
                        page.close()
                    }
                    thumbnailCache.put(cacheKey, bitmap)
                    bitmap
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Preloads pages around [centerIndex] into cache, ahead of when the user scrolls to them. */
    fun preloadAround(centerIndex: Int, targetWidthPx: Int, radius: Int = 2) {
        val count = _pageCount.value
        if (count == 0 || targetWidthPx <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            for (offset in -radius..radius) {
                val idx = centerIndex + offset
                if (idx in 0 until count) {
                    val key = PdfBitmapCache.key(idx, targetWidthPx)
                    if (!pageBitmapCache.containsFresh(key)) {
                        renderPage(idx, targetWidthPx)
                    }
                }
            }
        }
    }

    // ==================== Layout / display ====================

    fun setViewMode(mode: PdfViewMode) {
        _viewMode.value = mode
        preferences.setViewMode(mode)
    }

    fun setColorMode(mode: PdfColorMode) {
        _colorMode.value = mode
        preferences.setColorMode(mode)
    }

    fun toggleKeepScreenOn() {
        val newValue = !_keepScreenOn.value
        _keepScreenOn.value = newValue
        preferences.setKeepScreenOn(newValue)
    }

    fun toggleAutoBrightness() {
        val newValue = !_autoBrightness.value
        _autoBrightness.value = newValue
        preferences.setAutoBrightness(newValue)
    }

    fun setManualBrightness(value: Float) {
        val clamped = value.coerceIn(0.05f, 1f)
        _manualBrightness.value = clamped
        preferences.setManualBrightness(clamped)
    }

    fun setZoom(value: Float) {
        val clamped = value.coerceIn(0.5f, 6f)
        _zoom.value = clamped
        if (documentKey.isNotBlank()) preferences.setZoom(documentKey, clamped)
    }

    fun rotate() {
        val newRotation = (_rotation.value + 90) % 360
        _rotation.value = newRotation
        if (documentKey.isNotBlank()) preferences.setRotation(documentKey, newRotation)
    }

    fun toggleFullscreen() {
        _isFullscreen.value = !_isFullscreen.value
    }

    fun toggleThumbnailSidebar() {
        _isThumbnailSidebarOpen.value = !_isThumbnailSidebarOpen.value
        if (_isThumbnailSidebarOpen.value) _isBookmarksPanelOpen.value = false
    }

    fun toggleBookmarksPanel() {
        _isBookmarksPanelOpen.value = !_isBookmarksPanelOpen.value
        if (_isBookmarksPanelOpen.value) _isThumbnailSidebarOpen.value = false
    }

    // ==================== Page navigation / history / bookmarks ====================

    /** Called continuously as the user scrolls, to keep the current-page indicator accurate. Does not push history. */
    fun onPageVisible(pageIndex: Int) {
        if (pageIndex != _currentPage.value) {
            _currentPage.value = pageIndex
            if (documentKey.isNotBlank()) preferences.setLastPage(documentKey, pageIndex)
        }
    }

    /** Explicit jump (from search, bookmarks, thumbnails, page navigator) — pushes history and tells the UI to scroll. */
    fun jumpToPage(pageIndex: Int) {
        val target = pageIndex.coerceIn(0, (_pageCount.value - 1).coerceAtLeast(0))
        pushHistory(target)
        _currentPage.value = target
        viewModelScope.launch { _navigateToPage.emit(target) }
    }

    private fun pushHistory(pageIndex: Int) {
        _history.update { current ->
            val withoutDup = current.filterNot { it.pageIndex == pageIndex }
            (withoutDup + PdfHistoryEntry(pageIndex)).takeLast(MAX_HISTORY)
        }
    }

    fun toggleBookmark(pageIndex: Int) {
        _bookmarks.update { current ->
            val updated = if (pageIndex in current) current - pageIndex else current + pageIndex
            if (documentKey.isNotBlank()) preferences.setBookmarks(documentKey, updated)
            updated
        }
    }

    fun isBookmarked(pageIndex: Int): Boolean = pageIndex in _bookmarks.value

    // ==================== Search ====================

    fun setSearchActive(active: Boolean) {
        _isSearchActive.value = active
        if (!active) clearSearch()
    }

    fun search(query: String) {
        _searchQuery.value = query
        val file = documentFile
        if (query.isBlank() || file == null) {
            _searchResults.value = emptyList()
            _currentSearchMatchIndex.value = -1
            return
        }
        viewModelScope.launch {
            _isSearching.value = true
            val results = searchEngine.search(file, query)
            _searchResults.value = results
            _currentSearchMatchIndex.value = if (results.isEmpty()) -1 else 0
            _isSearching.value = false
            results.firstOrNull()?.let { jumpToPage(it.pageIndex) }
        }
    }

    fun nextSearchMatch() {
        val results = _searchResults.value
        if (results.isEmpty()) return
        val next = (_currentSearchMatchIndex.value + 1) % results.size
        _currentSearchMatchIndex.value = next
        jumpToPage(results[next].pageIndex)
    }

    fun previousSearchMatch() {
        val results = _searchResults.value
        if (results.isEmpty()) return
        val prev = (_currentSearchMatchIndex.value - 1 + results.size) % results.size
        _currentSearchMatchIndex.value = prev
        jumpToPage(results[prev].pageIndex)
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _currentSearchMatchIndex.value = -1
    }

    // ==================== URI resolution ====================

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            val name = cursor.getString(nameIndex)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
            } catch (e: Exception) {
                // Fall through to path-segment fallback below.
            }
        }
        val lastSegment = uri.lastPathSegment
        return lastSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && !it.startsWith("msf:") }
            ?: "Document"
    }

    /**
     * Resolves any URI (file:// or content://, including providers like the Downloads
     * provider's msf: URIs that block direct PFD access) to a local cache File.
     */
    private fun resolvePdfToLocalFile(uri: Uri): File? {
        if (uri.scheme == "file") {
            val path = uri.path
            if (path != null) {
                val file = File(path)
                if (file.exists() && file.canRead()) return file
            }
        }
        return try {
            val tmpFile = File(appContext.cacheDir, "pdf_view_${uri.hashCode()}_${System.currentTimeMillis()}.pdf")
            val opened = appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
                true
            } ?: false
            if (opened && tmpFile.exists() && tmpFile.length() > 0) tmpFile else null
        } catch (e: Exception) {
            null
        }
    }
}
