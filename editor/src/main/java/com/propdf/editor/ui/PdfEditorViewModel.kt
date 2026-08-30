package com.propdf.editor.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.CompressConfig
import com.propdf.core.domain.model.CropConfig
import com.propdf.core.domain.model.MeasurementUnit
import com.propdf.core.domain.model.PageNumberConfig
import com.propdf.core.domain.model.PageNumberPosition
import com.propdf.core.domain.model.WatermarkConfig
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppResult
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
    @ApplicationContext private val context: Context,
    // The interface lives in :core (which :editor already depends on); the only
    // @Binds implementation is compiled in :app (PdfOperationsRepositoryImpl). This
    // does NOT require :editor to depend on :app: Hilt/Dagger resolves @InstallIn
    // (SingletonComponent) bindings once, in the final :app module's merged
    // component, regardless of which Gradle module a given @Module is compiled in.
    // :editor's compiled code only ever references the AppResult<Uri>-returning
    // interface type from :core, so this compiles and resolves correctly. A prior
    // pass here concluded this was unreachable without moving the binding down into
    // :core -- that conflated "which Gradle module compiles this class" with "which
    // Gradle module the DI graph is resolved in"; those are different, and the
    // cross-module @Binds-in-a-downstream-module pattern is standard multi-module
    // Hilt. Verified against PdfOperationsModule.kt (app/.../di) and the interface
    // declaration in core/.../domain/repository/PdfOperationsRepository.kt.
    private val pdfOperationsRepository: PdfOperationsRepository
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

    // ==================== Extract / Compress / Watermark / Page numbers / Crop ====================
    //
    // These are Uri-in/Uri-out operations against PdfOperationsRepository (see the
    // constructor comment for why this ViewModel can inject that interface). Extract
    // produces a separate output file (same "new document" model as Merge/Split, which
    // already navigate away from this screen), so it's left as-is and reported via
    // operationMessage. Compress/Watermark/Page numbers/Crop conceptually edit the
    // document that's already open in this screen, so their result is written back over
    // currentUri and the editor reloads from it -- the same overwrite saveDocument()
    // already performs, just triggered by a tool instead of the Save button.

    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage.asStateFlow()

    private val _operationInProgress = MutableStateFlow(false)
    val operationInProgress: StateFlow<Boolean> = _operationInProgress.asStateFlow()

    fun consumeOperationMessage() {
        _operationMessage.value = null
    }

    fun extractSelectedPages(pages: List<Int>) {
        val uri = currentUri
        if (uri == null || pages.isEmpty()) {
            _operationMessage.value = "Select at least one page to extract"
            return
        }
        viewModelScope.launch {
            _operationInProgress.value = true
            val outputName = "Extracted_${System.currentTimeMillis()}.pdf"
            when (val result = pdfOperationsRepository.extractPages(uri, pages, outputName)) {
                is AppResult.Success -> _operationMessage.value = "Saved extracted pages as $outputName"
                is AppResult.Error -> _operationMessage.value = result.message ?: "Extract failed"
                is AppResult.Loading -> {}
            }
            _operationInProgress.value = false
        }
    }

    fun compressDocument(config: CompressConfig = CompressConfig()) {
        runInPlaceOperation("Compress failed") { uri -> pdfOperationsRepository.compressPdf(uri, config) }
    }

    fun applyWatermark(text: String, opacity: Float = 0.3f, rotation: Float = 45f) {
        if (text.isBlank()) {
            _operationMessage.value = "Enter watermark text"
            return
        }
        val config = WatermarkConfig(text = text, opacity = opacity, rotation = rotation)
        runInPlaceOperation("Watermark failed") { uri -> pdfOperationsRepository.addWatermark(uri, config) }
    }

    fun applyPageNumbers(format: String = "Page %d of %d", position: PageNumberPosition = PageNumberPosition.BOTTOM_CENTER) {
        val config = PageNumberConfig(format = format, position = position)
        runInPlaceOperation("Page numbers failed") { uri -> pdfOperationsRepository.addPageNumbers(uri, config) }
    }

    fun cropDocument(marginPt: Float, pages: List<Int>) {
        val doc = document
        val targetPages = pages.ifEmpty { doc?.let { (0 until it.numberOfPages).toList() } ?: emptyList() }
        if (targetPages.isEmpty()) {
            _operationMessage.value = "No pages to crop"
            return
        }
        val config = CropConfig(
            leftMargin = marginPt,
            rightMargin = marginPt,
            topMargin = marginPt,
            bottomMargin = marginPt,
            unit = MeasurementUnit.POINT
        )
        val uri = currentUri
        if (uri == null) {
            _operationMessage.value = "Crop failed"
            return
        }
        viewModelScope.launch {
            _operationInProgress.value = true
            when (val result = pdfOperationsRepository.cropPages(uri, targetPages, config)) {
                is AppResult.Success -> overwriteCurrentDocument(result.data, "Cropped")
                is AppResult.Error -> _operationMessage.value = result.message ?: "Crop failed"
                is AppResult.Loading -> {}
            }
            _operationInProgress.value = false
        }
    }

    /** Runs a Uri-in/Uri-out repository call against the currently open document and,
     * on success, overwrites currentUri with the result and reloads it in place. */
    private fun runInPlaceOperation(errorLabel: String, op: suspend (Uri) -> AppResult<Uri>) {
        val uri = currentUri ?: return
        viewModelScope.launch {
            _operationInProgress.value = true
            when (val result = op(uri)) {
                is AppResult.Success -> overwriteCurrentDocument(result.data, errorLabel.removeSuffix(" failed"))
                is AppResult.Error -> _operationMessage.value = result.message ?: errorLabel
                is AppResult.Loading -> {}
            }
            _operationInProgress.value = false
        }
    }

    private suspend fun overwriteCurrentDocument(resultUri: Uri, actionLabel: String) {
        val target = currentUri ?: return
        try {
            withContext(Dispatchers.IO) {
                document?.close()
                document = null
                context.contentResolver.openInputStream(resultUri)?.use { input ->
                    context.contentResolver.openOutputStream(target)?.use { output ->
                        input.copyTo(output)
                    } ?: throw IllegalStateException("Cannot write to document")
                } ?: throw IllegalStateException("Cannot read operation result")
            }
            _operationMessage.value = "$actionLabel"
            loadDocument(target)
        } catch (e: Exception) {
            _operationMessage.value = e.message ?: "$actionLabel failed"
        }
    }

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
