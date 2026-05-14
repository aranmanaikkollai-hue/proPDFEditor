package com.propdf.editor.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFilesRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.result.onError
import com.propdf.core.domain.result.onSuccess
import com.propdf.editor.utils.FileHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recentFilesRepo: RecentFilesRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        observeFiles()
    }

    private fun observeFiles() {
        viewModelScope.launch(dispatchers.io) {
            combine(
                recentFilesRepo.observeAll(),
                recentFilesRepo.observeCategories()
            ) { files, categories ->
                files to categories
            }.collectLatest { (files, categories) ->
                applyFilters(files, categories)
            }
        }
    }

    private fun applyFilters(allFiles: List<RecentFile>, categories: List<String>) {
        val state = _uiState.value
        var filtered = allFiles

        // Apply tab filter
        filtered = when (state.currentTab) {
            Tab.RECENT -> filtered.sortedByDescending { it.lastOpenedAt }
            Tab.STARRED -> filtered.filter { it.isFavourite }
            Tab.CATEGORIES -> {
                if (state.categoryDetail.isEmpty()) {
                    // Show category list
                    _uiState.update { it.copy(
                        files = categories.map { cat ->
                            RecentFile(
                                uri = "category://$cat",
                                displayName = cat,
                                fileSizeBytes = 0,
                                lastOpenedAt = 0,
                                pageCount = allFiles.count { f -> f.category == cat }
                            )
                        },
                        allCategories = categories
                    )}
                    return
                } else {
                    filtered.filter { it.category == state.categoryDetail }
                }
            }
            Tab.BOOKMARKS -> {
                filtered
            }
        }

        // Apply search
        if (state.searchQuery.isNotEmpty()) {
            filtered = filtered.filter { it.displayName.contains(state.searchQuery, ignoreCase = true) }
        }

        // Apply sort
        filtered = when (state.sortField) {
            SortField.DATE -> if (state.sortAsc) filtered.sortedBy { it.lastOpenedAt } else filtered.sortedByDescending { it.lastOpenedAt }
            SortField.NAME -> if (state.sortAsc) filtered.sortedBy { it.displayName.lowercase() } else filtered.sortedByDescending { it.displayName.lowercase() }
            SortField.SIZE -> if (state.sortAsc) filtered.sortedBy { it.fileSizeBytes } else filtered.sortedByDescending { it.fileSizeBytes }
        }

        _uiState.update { it.copy(files = filtered, allCategories = categories) }
    }

    fun setTab(tab: Tab) {
        _uiState.update { it.copy(currentTab = tab, categoryDetail = if (tab == Tab.CATEGORIES) "" else it.categoryDetail) }
        viewModelScope.launch(dispatchers.io) {
            recentFilesRepo.observeAll().collectLatest { applyFilters(it, _uiState.value.allCategories) }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        viewModelScope.launch(dispatchers.io) {
            recentFilesRepo.observeAll().collectLatest { applyFilters(it, _uiState.value.allCategories) }
        }
    }

    fun setSort(field: SortField, asc: Boolean) {
        _uiState.update { it.copy(sortField = field, sortAsc = asc) }
        viewModelScope.launch(dispatchers.io) {
            recentFilesRepo.observeAll().collectLatest { applyFilters(it, _uiState.value.allCategories) }
        }
    }

    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun openPdf(uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            val name = FileHelper.getFileName(context, uri) ?: "document.pdf"
            val pageCount = withContext(dispatchers.io) { countPdfPages(uri) }
            val size = withContext(dispatchers.io) { getUriSize(uri) }
            recentFilesRepo.add(RecentFile(
                uri = uri.toString(),
                displayName = name,
                fileSizeBytes = size,
                lastOpenedAt = System.currentTimeMillis(),
                pageCount = pageCount
            ))
            _uiState.update { it.copy(launchViewerUri = uri.toString()) }
        }
    }

    fun openPdfString(uriStr: String) {
        try {
            openPdf(Uri.parse(uriStr))
        } catch (_: Exception) {
            _uiState.update { it.copy(errorMessage = "Cannot open file") }
        }
    }

    fun toggleFavourite(uri: String) {
        viewModelScope.launch(dispatchers.io) {
            val result = recentFilesRepo.getByUri(uri)
            if (result is AppResult.Success) {
                val data = result.data
                if (data != null) {
                    recentFilesRepo.setFavourite(uri, !data.isFavourite)
                }
            }
        }
    }

    fun setCategory(uri: String, category: String) {
        viewModelScope.launch(dispatchers.io) {
            recentFilesRepo.setCategory(uri, category)
        }
    }

    fun renameFile(uri: String, newName: String) {
        viewModelScope.launch(dispatchers.io) {
            val result = recentFilesRepo.getByUri(uri)
            if (result is AppResult.Success) {
                val data = result.data
                if (data != null) {
                    recentFilesRepo.add(data.copy(displayName = newName))
                }
            }
        }
    }

    fun deleteFile(uri: String) {
        viewModelScope.launch(dispatchers.io) {
            recentFilesRepo.remove(uri)
        }
    }

    fun clearRecent() {
        viewModelScope.launch(dispatchers.io) {
            recentFilesRepo.clearRecentOnly()
        }
    }

    fun clearAll() {
        viewModelScope.launch(dispatchers.io) {
            recentFilesRepo.clearAll()
        }
    }

    fun createBlankPdf(count: Int) {
        viewModelScope.launch(dispatchers.io) {
            val uri = saveBlankPdf(count)
            uri?.let { openPdf(it) }
        }
    }

    fun createFromImages() {
        _uiState.update { it.copy(showImagePicker = true) }
    }

    fun onImagePickerResult(uris: List<Uri>) {
        viewModelScope.launch(dispatchers.io) {
            val uri = imagesToPdf(uris)
            uri?.let { openPdf(it) }
        }
    }

    fun onViewerLaunched() {
        _uiState.update { it.copy(launchViewerUri = null) }
    }

    fun onImagePickerShown() {
        _uiState.update { it.copy(showImagePicker = false) }
    }

    // ===================== PDF HELPERS =====================
    private fun countPdfPages(uri: Uri): Int {
        return try {
            val pfd = if (uri.scheme == "content") {
                context.contentResolver.openFileDescriptor(uri, "r")
            } else {
                android.os.ParcelFileDescriptor.open(File(uri.path ?: return 0), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            } ?: return 0
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            val count = renderer.pageCount
            renderer.close()
            pfd.close()
            count
        } catch (_: Exception) { 0 }
    }

    private fun getUriSize(uri: Uri): Long {
        return try {
            if (uri.scheme == "file") File(uri.path ?: return 0).length()
            else context.contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: 0
        } catch (_: Exception) { 0 }
    }

    private fun saveBlankPdf(count: Int): Uri? {
        val doc = PdfDocument()
        return try {
            for (i in 1..count) {
                val pi = PdfDocument.PageInfo.Builder(612, 792, i).create()
                val page = doc.startPage(pi)
                page.canvas.drawColor(android.graphics.Color.WHITE)
                doc.finishPage(page)
            }
            val fileName = "Blank_${System.currentTimeMillis()}.pdf"
            var outUri: Uri? = null
            val out: java.io.OutputStream? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ProPDF")
                }
                outUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                outUri?.let { context.contentResolver.openOutputStream(it) }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ProPDF").also { it.mkdirs() }
                val outFile = File(dir, fileName)
                outUri = Uri.fromFile(outFile)
                FileOutputStream(outFile)
            }
            if (out == null) return null
            out.use { doc.writeTo(it) }
            outUri
        } catch (_: Exception) { null } finally { doc.close() }
    }

    private fun imagesToPdf(uris: List<Uri>): Uri? {
        val doc = PdfDocument()
        return try {
            uris.forEachIndexed { i, uri ->
                val bmp = context.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                } ?: return null
                val pi = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
                val page = doc.startPage(pi)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)
                bmp.recycle()
            }
            val fileName = "Images_${System.currentTimeMillis()}.pdf"
            var outUri: Uri? = null
            val out: java.io.OutputStream? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ProPDF")
                }
                outUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                outUri?.let { context.contentResolver.openOutputStream(it) }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ProPDF").also { it.mkdirs() }
                val outFile = File(dir, fileName)
                outUri = Uri.fromFile(outFile)
                FileOutputStream(outFile)
            }
            if (out == null) return null
            out.use { doc.writeTo(it) }
            outUri
        } catch (_: Exception) { null } finally { doc.close() }
    }
}

// ===================== UI STATE =====================
data class MainUiState(
    val isLoading: Boolean = false,
    val files: List<RecentFile> = emptyList(),
    val allCategories: List<String> = emptyList(),
    val currentTab: Tab = Tab.RECENT,
    val categoryDetail: String = "",
    val searchQuery: String = "",
    val sortField: SortField = SortField.DATE,
    val sortAsc: Boolean = false,
    val viewMode: ViewMode = ViewMode.LIST,
    val launchViewerUri: String? = null,
    val showImagePicker: Boolean = false,
    val errorMessage: String? = null
) {
    val sortLabel: String
        get() = when (sortField) {
            SortField.DATE -> if (sortAsc) "Date (oldest first)" else "Date (newest first)"
            SortField.NAME -> if (sortAsc) "Name (A-Z)" else "Name (Z-A)"
            SortField.SIZE -> if (sortAsc) "Size (smallest first)" else "Size (largest first)"
        }
}

enum class Tab(val label: String) {
    RECENT("Recent"),
    STARRED("Starred"),
    CATEGORIES("Categories"),
    BOOKMARKS("Bookmarks")
}

enum class SortField { DATE, NAME, SIZE }
enum class ViewMode { LIST, GRID, TILE }
