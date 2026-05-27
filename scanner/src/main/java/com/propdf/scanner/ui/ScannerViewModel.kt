package com.propdf.scanner.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.ScannedPage
import com.propdf.scanner.engine.ColorMode
import com.propdf.scanner.engine.DocumentScannerEngine
import com.propdf.scanner.engine.ScanOptions
import com.propdf.scanner.engine.ScannedDocument
import com.propdf.scanner.engine.ocr.MlKitOcrEngine
import com.propdf.scanner.engine.pdf.SearchablePdfGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val scannerEngine: DocumentScannerEngine,
    private val pdfGenerator: SearchablePdfGenerator,
    private val ocrEngine: MlKitOcrEngine
) : ViewModel() {

    // Wrapper to hold both metadata (ScannedPage) and bitmaps
    data class CapturedPage(
        val meta: ScannedPage,
        val bitmap: Bitmap,
        val originalBitmap: Bitmap
    )

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val _capturedPages = MutableStateFlow<List<CapturedPage>>(emptyList())
    val capturedPages: StateFlow<List<CapturedPage>> = _capturedPages.asStateFlow()

    private val _currentPage = MutableStateFlow<CapturedPage?>(null)
    val currentPage: StateFlow<CapturedPage?> = _currentPage.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _ocrText = MutableStateFlow("")
    val ocrText: StateFlow<String> = _ocrText.asStateFlow()

    fun captureDocument(bitmap: Bitmap, options: ScanOptions = ScanOptions()) {
        viewModelScope.launch {
            _isProcessing.value = true
            _progress.value = 0f

            val result = scannerEngine.scanDocument(bitmap, options)

            result.onSuccess { doc ->
                val meta = ScannedPage(
                    index = _capturedPages.value.size,
                    width = doc.bitmap.width,
                    height = doc.bitmap.height,
                    rotation = 0
                )
                val page = CapturedPage(
                    meta = meta,
                    bitmap = doc.bitmap,
                    originalBitmap = doc.originalBitmap
                )
                _capturedPages.value = _capturedPages.value + page
                _currentPage.value = page
                _uiState.value = _uiState.value.copy(error = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
            }

            _isProcessing.value = false
            _progress.value = 1f
        }
    }

    fun applyFilterToCurrent(filter: ColorMode) {
        val page = _currentPage.value ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            val filtered = scannerEngine.applyFilter(page.originalBitmap, filter)
            val updated = page.copy(bitmap = filtered)
            updatePage(updated)
            _currentPage.value = updated
            _isProcessing.value = false
        }
    }

    fun rotateCurrent(degrees: Float) {
        val page = _currentPage.value ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            val rotated = scannerEngine.rotate(page.bitmap, degrees)
            val updated = page.copy(bitmap = rotated)
            updatePage(updated)
            _currentPage.value = updated
            _isProcessing.value = false
        }
    }

    fun adjustBrightnessCurrent(delta: Int) {
        val page = _currentPage.value ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            val adjusted = scannerEngine.adjustBrightness(page.originalBitmap, delta)
            val updated = page.copy(bitmap = adjusted)
            updatePage(updated)
            _currentPage.value = updated
            _isProcessing.value = false
        }
    }

    fun removePage(pageIndex: Int) {
        _capturedPages.value = _capturedPages.value.filter { it.meta.index != pageIndex }
        if (_currentPage.value?.meta?.index == pageIndex) {
            _currentPage.value = _capturedPages.value.lastOrNull()
        }
    }

    fun reorderPages(newOrder: List<Int>) {
        val pages = _capturedPages.value
        _capturedPages.value = newOrder.mapNotNull { idx -> pages.find { it.meta.index == idx } }
    }

    fun selectPage(pageIndex: Int) {
        _currentPage.value = _capturedPages.value.find { it.meta.index == pageIndex }
    }

    fun runOcrOnCurrent() {
        val page = _currentPage.value ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            val result = ocrEngine.extractText(page.bitmap)
            result.onSuccess { text ->
                _ocrText.value = text
                _uiState.value = _uiState.value.copy(error = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
            }
            _isProcessing.value = false
        }
    }

    fun generateSearchablePdf(fileName: String? = null) {
        val pages = _capturedPages.value
        if (pages.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "No pages to export")
            return
        }
        viewModelScope.launch {
            _isProcessing.value = true
            _progress.value = 0f

            val result = pdfGenerator.generateSearchablePdf(
                images = pages.map { it.bitmap },
                outputFileName = fileName ?: "Scan_${System.currentTimeMillis()}.pdf",
                onProgress = { current, total ->
                    _progress.value = current.toFloat() / total
                }
            )

            result.onSuccess { uri ->
                _uiState.value = _uiState.value.copy(lastOutputUri = uri, error = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
            }

            _isProcessing.value = false
            _progress.value = 1f
        }
    }

    fun generateImagePdf(fileName: String? = null) {
        val pages = _capturedPages.value
        if (pages.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "No pages to export")
            return
        }
        viewModelScope.launch {
            _isProcessing.value = true
            _progress.value = 0f

            val result = pdfGenerator.generateImagePdf(
                images = pages.map { it.bitmap },
                outputFileName = fileName ?: "Scan_${System.currentTimeMillis()}.pdf",
                onProgress = { current, total ->
                    _progress.value = current.toFloat() / total
                }
            )

            result.onSuccess { uri ->
                _uiState.value = _uiState.value.copy(lastOutputUri = uri, error = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
            }

            _isProcessing.value = false
            _progress.value = 1f
        }
    }

    fun saveAsJpegs() {
        _uiState.value = _uiState.value.copy(error = "JPEG export not yet implemented")
    }

    fun clearAll() {
        _capturedPages.value = emptyList()
        _currentPage.value = null
        _ocrText.value = ""
        _uiState.value = ScannerUiState()
    }

    private fun updatePage(updated: CapturedPage) {
        _capturedPages.value = _capturedPages.value.map {
            if (it.meta.index == updated.meta.index) updated else it
        }
    }

    override fun onCleared() {
        super.onCleared()
        ocrEngine.close()
    }

    data class ScannerUiState(
        val lastOutputUri: Uri? = null,
        val error: String? = null
    )
}
