package com.propdf.editor.ui.forms.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdfeditor.core.util.toSafeUserMessage
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
 * Renders each page of the document being form-filled as a bitmap, using the same
 * android.graphics.pdf.PdfRenderer approach already used by RedactionViewModel (see that
 * file for why it's opened/closed per call rather than held open, and why width/height
 * are already in the same PDF-point units PdfFormField coordinates use). This is only page
 * rendering/navigation -- the actual form-field data, values, save and flatten logic all
 * already existed in PdfFormViewModel/PdfFormRepository/PdfFormEngine; this ViewModel does
 * not duplicate any of that.
 */
@HiltViewModel
class FormsHostViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(FormsHostUiState())
    val uiState: StateFlow<FormsHostUiState> = _uiState.asStateFlow()

    private var documentUri: Uri? = null

    fun loadDocument(uriString: String) {
        val uri = Uri.parse(uriString)
        documentUri = uri
        _uiState.value = FormsHostUiState(isLoading = true)

        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { getPageCount(uri) }
                _uiState.value = _uiState.value.copy(isLoading = false, pageCount = count)
                if (count > 0) goToPage(0)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.toSafeUserMessage("Unable to open this PDF.")
                )
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
                _uiState.value.copy(
                    isRenderingPage = false,
                    error = "Couldn't render page ${pageIndex + 1}."
                )
            }
        }
    }

    fun nextPage() = goToPage(_uiState.value.currentPage + 1)
    fun previousPage() = goToPage(_uiState.value.currentPage - 1)

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun getPageCount(uri: Uri): Int {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return 0
            renderer = PdfRenderer(pfd)
            return renderer.pageCount
        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    private fun renderPage(uri: Uri, pageIndex: Int): RenderedFormPage? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            renderer = PdfRenderer(pfd)
            if (pageIndex !in 0 until renderer.pageCount) return null
            val page = renderer.openPage(pageIndex)
            try {
                val widthPt = page.width.toFloat()
                val heightPt = page.height.toFloat()
                val renderScale = 2f
                val bitmap = Bitmap.createBitmap(
                    (widthPt * renderScale).toInt().coerceAtLeast(1),
                    (heightPt * renderScale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return RenderedFormPage(bitmap, widthPt, heightPt)
            } finally {
                page.close()
            }
        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    private data class RenderedFormPage(val bitmap: Bitmap, val widthPt: Float, val heightPt: Float)
}

data class FormsHostUiState(
    val isLoading: Boolean = false,
    val isRenderingPage: Boolean = false,
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val pageBitmap: Bitmap? = null,
    val pageWidthPt: Float = 0f,
    val pageHeightPt: Float = 0f,
    val error: String? = null
)
