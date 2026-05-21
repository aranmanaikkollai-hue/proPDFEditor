package com.propdf.scanner.ui

import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val _capturedPages = MutableStateFlow<List<ScannedPage>>(emptyList())
    val capturedPages: StateFlow<List<ScannedPage>> = _capturedPages.asStateFlow()

    private val _currentPage = MutableStateFlow<ScannedPage?>(null)
    val currentPage: StateFlow<ScannedPage?> = _currentPage.asStateFlow()

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
                val page = ScannedPage(
                    id = System.currentTimeMillis(),
                    bitmap = doc.bitmap,
                    originalBitmap = doc.originalBitmap,
                    corners = doc.detectedCorners,
                    filter = options.colorMode
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
            val updated = page.copy(bitmap = filtered, filter = filter)
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

    fun removePage(pageId: Long) {
        _capturedPages.value = _capturedPages.value.filter { it.id != pageId }
        if (_currentPage.value?.id == pageId) {
            _currentPage.value = _capturedPages.value.lastOrNull()
        }
    }

    fun reorderPages(newOrder: List<Long>) {
        val pages = _capturedPages.value
        _capturedPages.value = newOrder.mapNotNull { id -> pages.find { it.id == id } }
    }

    fun selectPage(pageId: Long) {
        _currentPage.value = _capturedPages.value.find { it.id == pageId }
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

    private fun updatePage(updated: ScannedPage) {
        _capturedPages.value = _capturedPages.value.map {
            if (it.id == updated.id) updated else it
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

    data class ScannedPage(
        val id: Long,
        val bitmap: Bitmap,
        val originalBitmap: Bitmap,
        val corners: List<PointF>?,
        val filter: ColorMode = ColorMode.AUTO
    )
}
