package com.propdf.editor.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PdfEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    // Real page-selection state driving the page grid in PdfEditorScreen. Previously the
    // screen had no selection model at all -- every operation button called into the
    // ViewModel with a hardcoded page (0, or an empty list), so "Delete"/"Duplicate"/
    // "Rotate" always acted on page 0 (or nothing) regardless of what the user tapped.
    private val _selectedPages = MutableStateFlow<Set<Int>>(emptySet())
    val selectedPages: StateFlow<Set<Int>> = _selectedPages.asStateFlow()

    // Low-DPI page thumbnails for the page grid, keyed by page index. Rendered off the
    // main thread with PDFBox's PDFRenderer (same API already used for this purpose in
    // PdfConverter.kt / PdfRepositoryImpl.kt / BatchOperationsWorker.kt elsewhere in this
    // codebase) so the editor can show a real page-management workflow instead of a bare
    // page count.
    private val _thumbnails = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<Int, Bitmap>> = _thumbnails.asStateFlow()

    private var document: PDDocument? = null
    private var currentUri: Uri? = null

    init {
        PDFBoxResourceLoader.init(context)
    }

    fun loadDocument(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = EditorUiState.Loading
            _selectedPages.value = emptySet()
            _thumbnails.value = emptyMap()
            try {
                val doc = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        PDDocument.load(stream)
                    } ?: throw IllegalStateException("Cannot open document")
                }
                document = doc
                currentUri = uri
                _uiState.value = EditorUiState.Ready(
                    pageCount = doc.numberOfPages,
                    uri = uri.toString()
                )
                renderThumbnails()
            } catch (e: Exception) {
                _uiState.value = EditorUiState.Error(e.message ?: "Failed to load PDF")
            }
        }
    }

    /**
     * Renders one low-res thumbnail per page. Runs on IO and publishes incrementally so
     * the grid can populate progressively on large documents instead of blocking on the
     * whole document at once.
     */
    private fun renderThumbnails() {
        val doc = document ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val renderer = PDFRenderer(doc)
            for (pageIndex in 0 until doc.numberOfPages) {
                try {
                    val bitmap = renderer.renderImageWithDPI(pageIndex, 36f)
                    _thumbnails.value = _thumbnails.value + (pageIndex to bitmap)
                } catch (_: Exception) {
                    // Skip a page whose render fails (e.g. a malformed page); the rest of
                    // the grid still populates and the page remains selectable by number.
                }
            }
        }
    }

    fun togglePageSelection(pageIndex: Int) {
        _selectedPages.value = _selectedPages.value.let {
            if (it.contains(pageIndex)) it - pageIndex else it + pageIndex
        }
    }

    fun selectAllPages() {
        val pageCount = (_uiState.value as? EditorUiState.Ready)?.pageCount ?: return
        _selectedPages.value = (0 until pageCount).toSet()
    }

    fun clearSelection() {
        _selectedPages.value = emptySet()
    }

    fun deletePages(pages: List<Int>) {
        if (pages.isEmpty()) return
        val doc = document ?: return
        viewModelScope.launch {
            try {
                pages.sortedDescending().forEach { index ->
                    if (index < doc.numberOfPages) {
                        doc.removePage(index)
                    }
                }
                _selectedPages.value = emptySet()
                _uiState.value = EditorUiState.Ready(
                    pageCount = doc.numberOfPages,
                    uri = currentUri.toString()
                )
                renderThumbnails()
            } catch (e: Exception) {
                _uiState.value = EditorUiState.Error(e.message ?: "Delete failed")
            }
        }
    }

    fun duplicatePage(pageIndex: Int) = duplicatePages(listOf(pageIndex))

    /**
     * Duplicates every selected page. Each duplicate is appended at the end of the
     * document, in ascending order of the original selection -- the same "import +
     * addPage" call already used (and confirmed working) by the original single-page
     * implementation. PDFBox-android's PDPageTree does have insert-at-position methods
     * upstream, but this codebase doesn't vendor the library source and there's no
     * network access in this environment to confirm the exact method name/signature
     * here, so this deliberately sticks to the append-only call already proven to work
     * in this file rather than guessing at an unverified API. Landing duplicates next to
     * their originals instead of at the end is a reasonable follow-up once that API can
     * actually be confirmed against the library.
     */
    fun duplicatePages(pages: List<Int>) {
        if (pages.isEmpty()) return
        val doc = document ?: return
        viewModelScope.launch {
            try {
                pages.sorted().forEach { original ->
                    if (original < doc.numberOfPages) {
                        val page = doc.getPage(original)
                        val imported = doc.importPage(page)
                        doc.addPage(imported)
                    }
                }
                _selectedPages.value = emptySet()
                _uiState.value = EditorUiState.Ready(
                    pageCount = doc.numberOfPages,
                    uri = currentUri.toString()
                )
                renderThumbnails()
            } catch (e: Exception) {
                _uiState.value = EditorUiState.Error(e.message ?: "Duplicate failed")
            }
        }
    }

    fun rotatePage(pageIndex: Int) = rotatePages(listOf(pageIndex))

    /** Rotates every selected page 90 degrees clockwise. */
    fun rotatePages(pages: List<Int>) {
        if (pages.isEmpty()) return
        val doc = document ?: return
        viewModelScope.launch {
            try {
                pages.forEach { index ->
                    if (index < doc.numberOfPages) {
                        val page = doc.getPage(index)
                        page.rotation = (page.rotation + 90) % 360
                    }
                }
                _uiState.value = EditorUiState.Ready(
                    pageCount = doc.numberOfPages,
                    uri = currentUri.toString()
                )
                renderThumbnails()
            } catch (e: Exception) {
                _uiState.value = EditorUiState.Error(e.message ?: "Rotate failed")
            }
        }
    }

    // ==================== Not available in this module ====================
    //
    // Extract/Compress/Watermark/Page-numbers/Crop are NOT implemented here on purpose,
    // not left broken by omission. A real, comprehensive implementation of every one of
    // these already exists as com.propdf.core.domain.repository.PdfOperationsRepository
    // (extractPages, compressPdf, addWatermark, addPageNumbers, cropPages, all Uri-based
    // and already used elsewhere in the app) -- but its only implementation and Hilt
    // binding live in the :app module's com.propdf.editor.data.repository package, and
    // this :editor Gradle module only depends on :core (see editor/build.gradle), which
    // has the interface but not the binding. :editor cannot depend on :app (that would be
    // a circular module dependency), so this ViewModel cannot reach that implementation
    // as configured.
    //
    // Real fix (not done here, to avoid an unverified cross-module DI restructuring):
    // move PdfOperationsRepositoryImpl's @Binds/@Provides Hilt binding from :app's
    // RepositoryModule down into :core's di package (core/src/main/java/com/propdf/core/di),
    // alongside the interface it already declares, so every module that depends on :core
    // -- including :editor -- can inject it. Until then these actions surface an honest
    // "not available" state in PdfEditorScreen rather than silently no-op-ing.

    fun saveDocument() {
        val doc = document ?: return
        viewModelScope.launch {
            try {
                currentUri?.let { uri ->
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        doc.save(output)
                    }
                }
                _uiState.value = EditorUiState.Saved(currentUri.toString())
            } catch (e: Exception) {
                _uiState.value = EditorUiState.Error(e.message ?: "Save failed")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            document?.close()
        } catch (_: Exception) { }
    }
}

sealed interface EditorUiState {
    data object Loading : EditorUiState
    data class Ready(val pageCount: Int, val uri: String) : EditorUiState
    data class Saved(val uri: String) : EditorUiState
    data class Error(val message: String) : EditorUiState
}
