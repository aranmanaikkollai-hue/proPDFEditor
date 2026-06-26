package com.propdfeditor.scanner.presentation

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdfeditor.scanner.domain.model.BatchScanSession
import com.propdfeditor.scanner.domain.model.EdgeDetectionResult
import com.propdfeditor.scanner.domain.model.ProcessingState
import com.propdfeditor.scanner.domain.model.ScannedPage
import com.propdfeditor.scanner.domain.model.ScanMode
import com.propdfeditor.scanner.domain.model.ScannerCameraConfig
import com.propdfeditor.scanner.domain.model.ScannerUiState
import com.propdfeditor.scanner.domain.usecase.BatchScanUseCase
import com.propdfeditor.scanner.domain.usecase.DeleteScanPageUseCase
import com.propdfeditor.scanner.domain.usecase.DetectEdgesUseCase
import com.propdfeditor.scanner.domain.usecase.ProcessScanUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val detectEdgesUseCase: DetectEdgesUseCase,
    private val processScanUseCase: ProcessScanUseCase,
    private val batchScanUseCase: BatchScanUseCase,
    private val deleteScanPageUseCase: DeleteScanPageUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScannerUiState>(ScannerUiState.Idle)
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val _cameraConfig = MutableStateFlow(ScannerCameraConfig())
    val cameraConfig: StateFlow<ScannerCameraConfig> = _cameraConfig.asStateFlow()

    private val _batchSession = MutableStateFlow<BatchScanSession?>(null)
    val batchSession: StateFlow<BatchScanSession?> = _batchSession.asStateFlow()

    private val _processingProgress = MutableStateFlow(0)
    val processingProgress: StateFlow<Int> = _processingProgress.asStateFlow()

    private val _isFlashOn = MutableStateFlow(false)
    val isFlashOn: StateFlow<Boolean> = _isFlashOn.asStateFlow()

    private var currentScanMode: ScanMode = ScanMode.AUTO

    fun initializeScanner() {
        _uiState.value = ScannerUiState.Preview(
            isFlashOn = _isFlashOn.value,
            isAutoCapture = _cameraConfig.value.enableAutoCapture
        )
    }

    fun setScanMode(mode: ScanMode) {
        currentScanMode = mode
        if (mode == ScanMode.BATCH && _batchSession.value == null) {
            _batchSession.value = BatchScanSession(id = UUID.randomUUID().toString())
        }
    }

    fun toggleFlash() {
        val newState = !_isFlashOn.value
        _isFlashOn.value = newState
        val currentState = _uiState.value
        if (currentState is ScannerUiState.Preview) {
            _uiState.value = currentState.copy(isFlashOn = newState)
        }
    }

    fun resetScanner() {
        _uiState.value = ScannerUiState.Preview(
            isFlashOn = _isFlashOn.value,
            isAutoCapture = _cameraConfig.value.enableAutoCapture
        )
        _processingProgress.value = 0
    }

    fun onImageCaptured(_context: Context, bitmap: Bitmap) {
        viewModelScope.launch {
            val pageId = UUID.randomUUID().toString()
            _uiState.value = ScannerUiState.Processing(pageId = pageId, stage = "Detecting edges...")
            _processingProgress.value = 10

            try {
                val _edgeResult = detectEdgesUseCase(bitmap)
                _processingProgress.value = 60

                _uiState.value = ScannerUiState.Processing(pageId = pageId, stage = "Processing...")
                _processingProgress.value = 90

                val placeholderUri = Uri.EMPTY
                val page = ScannedPage(
                    id = pageId,
                    originalUri = placeholderUri,
                    scanMode = currentScanMode,
                    processingState = ProcessingState.COMPLETE,
                    width = bitmap.width,
                    height = bitmap.height
                )

                _processingProgress.value = 100
                _uiState.value = ScannerUiState.Review(page = page)

            } catch (e: Exception) {
                _uiState.value = ScannerUiState.Error(
                    message = e.message ?: "Processing failed",
                    recoverable = true
                )
            }
        }
    }

    fun previewEdgeDetection(bitmap: Bitmap, callback: (EdgeDetectionResult) -> Unit) {
        viewModelScope.launch {
            try {
                val result = detectEdgesUseCase(bitmap)
                callback(result)
            } catch (_: Exception) {
                // Silent — preview frames are non-critical
            }
        }
    }

    fun addToBatchAndContinue() {
        val reviewState = _uiState.value as? ScannerUiState.Review ?: return
        val session = _batchSession.value ?: BatchScanSession(id = UUID.randomUUID().toString())
        _batchSession.value = session.copy(pages = session.pages + reviewState.page)
        resetScanner()
    }

    fun completeBatch() {
        val session = _batchSession.value
        if (session != null && session.pages.isNotEmpty()) {
            _uiState.value = ScannerUiState.BatchReview(session = session)
        } else {
            val reviewState = _uiState.value as? ScannerUiState.Review
            val page = reviewState?.page
            val finalSession = BatchScanSession(
                id = UUID.randomUUID().toString(),
                pages = if (page != null) listOf(page) else emptyList()
            )
            _uiState.value = ScannerUiState.BatchReview(session = finalSession)
        }
    }

    fun deletePage(pageId: String) {
        viewModelScope.launch {
            val page = _batchSession.value?.pages?.find { it.id == pageId } ?: return@launch
            deleteScanPageUseCase(page)
            _batchSession.value = _batchSession.value?.let { session ->
                session.copy(pages = session.pages.filter { it.id != pageId })
            }
        }
    }
}
