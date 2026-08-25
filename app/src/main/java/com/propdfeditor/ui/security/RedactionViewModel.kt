package com.propdfeditor.ui.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.security.data.entity.RedactionEntity
import com.propdf.security.data.repository.SecurityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Backs the interactive Redaction screen.
 *
 * Both halves of this feature already existed and were fully real:
 * - SecurityRepository.addRedaction/getPendingRedactions/applyRedactions (:security module,
 *   Room-backed, iText-backed) -- previously only reachable from the old Fragment UI
 *   (RedactionFragment/RedactionAdapter), not from the Compose Security Hub.
 * - RedactionOverlayView (:security) -- a working drag-to-mark-rectangle Android View with
 *   its own touch handling -- previously not embedded in any screen at all.
 *
 * This ViewModel just supplies the missing piece: rendering each page as a bitmap (via the
 * platform's android.graphics.pdf.PdfRenderer, already used elsewhere in the app) and
 * converting between the overlay view's on-screen pixel coordinates and the PDF's own
 * point-based, bottom-left-origin coordinate space that SecurityRepository.applyRedactions
 * actually draws with.
 */
@HiltViewModel
class RedactionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securityRepository: SecurityRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RedactionUiState())
    val uiState: StateFlow<RedactionUiState> = _uiState.asStateFlow()

    private val _pendingRedactions = MutableStateFlow<List<RedactionEntity>>(emptyList())
    val pendingRedactions: StateFlow<List<RedactionEntity>> = _pendingRedactions.asStateFlow()

    private var documentUri: Uri? = null
    private var documentUriString: String = ""

    fun loadDocument(uriString: String) {
        val uri = Uri.parse(uriString)
        documentUri = uri
        documentUriString = uriString
        _uiState.value = RedactionUiState(isLoading = true)

        viewModelScope.launch {
            securityRepository.getPendingRedactions(uriString).collect { _pendingRedactions.value = it }
        }

        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { getPageCount(uri) }
                _uiState.value = _uiState.value.copy(isLoading = false, pageCount = count)
                if (count > 0) goToPage(0)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Failed to open PDF")
            }
        }
    }

    fun goToPage(pageIndex: Int) {
        val uri = documentUri ?: return
        if (pageIndex < 0 || pageIndex >= _uiState.value.pageCount) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRenderingPage = true)
            val rendered = withContext(Dispatchers.IO) { renderPage(uri, pageIndex) }
            _uiState.value = if (rendered != null) {
                _uiState.value.copy(
                    currentPage = pageIndex,
                    pageBitmap = rendered.bitmap,
                    pageWidthPt = rendered.widthPt,
                    pageHeightPt = rendered.heightPt,
                    isRenderingPage = false
                )
            } else {
                _uiState.value.copy(isRenderingPage = false, error = "Couldn't render page ${pageIndex + 1}")
            }
        }
    }

    fun nextPage() = goToPage(_uiState.value.currentPage + 1)
    fun previousPage() = goToPage(_uiState.value.currentPage - 1)

    /**
     * Called when the user finishes dragging a new redaction box on [RedactionOverlayView].
     * [screenRect] is in that view's own pixel space; [viewWidthPx]/[viewHeightPx] are its
     * current on-screen size, needed to scale into PDF points and flip from the view's
     * top-left/y-down origin into a rect whose `.left/.bottom/.width()/.height()` -- the
     * only fields SecurityRepository.applyRedactions actually reads -- correctly describe
     * the box in PDF page space.
     */
    fun addRedactionFromView(screenRect: RectF, viewWidthPx: Float, viewHeightPx: Float) {
        val state = _uiState.value
        val widthPt = state.pageWidthPt
        val heightPt = state.pageHeightPt
        if (widthPt <= 0f || heightPt <= 0f || viewWidthPx <= 0f || viewHeightPx <= 0f) return

        val scaleX = widthPt / viewWidthPx
        val scaleY = heightPt / viewHeightPx

        val pdfLeft = screenRect.left * scaleX
        val pdfRight = screenRect.right * scaleX
        // PDF space has y=0 at the page bottom; the view has y=0 at the top. The box's
        // physically-lower edge (view-space "bottom", the larger y) becomes the smaller
        // PDF-space y -- this is the "lly" SecurityRepository reads via rect.bottom.
        val pdfBottom = heightPt - screenRect.bottom * scaleY
        val boxHeightPt = (screenRect.bottom - screenRect.top) * scaleY
        val pdfTop = pdfBottom - boxHeightPt

        val pdfRect = RectF(pdfLeft, pdfTop, pdfRight, pdfBottom)

        viewModelScope.launch {
            securityRepository.addRedaction(documentUriString, state.currentPage + 1, pdfRect)
        }
    }

    /** Inverse of [addRedactionFromView], used to redraw already-marked boxes on a page. */
    fun pdfRectToViewRect(pdfRect: RectF, viewWidthPx: Float, viewHeightPx: Float): RectF {
        val state = _uiState.value
        val widthPt = state.pageWidthPt
        val heightPt = state.pageHeightPt
        if (widthPt <= 0f || heightPt <= 0f) return RectF()

        val scaleX = viewWidthPx / widthPt
        val scaleY = viewHeightPx / heightPt

        val screenLeft = pdfRect.left * scaleX
        val screenRight = pdfRect.right * scaleX
        val screenBottom = (heightPt - pdfRect.bottom) * scaleY
        val screenTop = screenBottom - (pdfRect.height() / heightPt) * viewHeightPx

        return RectF(screenLeft, screenTop, screenRight, screenBottom)
    }

    fun applyRedactions(outputUri: Uri, permanent: Boolean = true) {
        val source = documentUri ?: return
        _uiState.value = _uiState.value.copy(isApplying = true)
        viewModelScope.launch {
            val result = if (permanent) {
                securityRepository.applyPermanentRedactions(source, outputUri)
            } else {
                securityRepository.applyRedactions(source, outputUri, permanent = false)
            }
            _uiState.value = result.fold(
                onSuccess = {
                    _uiState.value.copy(isApplying = false, completedUri = outputUri.toString(), message = "Redactions applied")
                },
                onFailure = { e ->
                    _uiState.value.copy(isApplying = false, message = e.message ?: "Failed to apply redactions")
                }
            )
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun getPageCount(uri: Uri): Int {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return 0
            renderer = PdfRenderer(pfd)
            return renderer.pageCount
        } finally {
            // PdfRenderer/ParcelFileDescriptor are not Closeable-friendly with `.use{}`
            // in a way that guarantees ordering here, so close explicitly in finally.
            renderer?.close()
            pfd?.close()
        }
    }

    private fun renderPage(uri: Uri, pageIndex: Int): RenderedPage? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            renderer = PdfRenderer(pfd)
            if (pageIndex !in 0 until renderer.pageCount) return null
            val page = renderer.openPage(pageIndex)
            try {
                // Page.getWidth()/getHeight() are in PDF points (1/72in), the same unit
                // PDF page geometry (and iText's Rectangle) uses, so no extra unit
                // conversion is needed once we scale a rendered bitmap back down by it.
                val widthPt = page.width.toFloat()
                val heightPt = page.height.toFloat()
                val renderScale = 2f // supersample a bit past 1pt-per-px for a crisper marking surface
                val bitmap = Bitmap.createBitmap(
                    (widthPt * renderScale).toInt().coerceAtLeast(1),
                    (heightPt * renderScale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return RenderedPage(bitmap, widthPt, heightPt)
            } finally {
                page.close()
            }
        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    private data class RenderedPage(val bitmap: Bitmap, val widthPt: Float, val heightPt: Float)
}

data class RedactionUiState(
    val isLoading: Boolean = false,
    val isRenderingPage: Boolean = false,
    val isApplying: Boolean = false,
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val pageBitmap: Bitmap? = null,
    val pageWidthPt: Float = 0f,
    val pageHeightPt: Float = 0f,
    val completedUri: String? = null,
    val message: String? = null,
    val error: String? = null
)
