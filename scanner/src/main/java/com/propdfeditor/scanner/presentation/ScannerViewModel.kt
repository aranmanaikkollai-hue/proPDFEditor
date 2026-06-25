package com.propdfeditor.scanner.presentation

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdfeditor.scanner.domain.model.BatchScanSession
import com.propdfeditor.scanner.domain.model.EdgeDetectionResult
import com.propdfeditor.scanner.domain.model.FlashMode
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

    /** Called by Activity in onCreate to set up any initial scanner state. */
    fun initializeScanner() {
        _uiState.value = ScannerUiState.Preview()
    }

    /** Switch the current scan mode (Document, Receipt, Whiteboard, Batch, Auto). */
    fun setScanMode(mode: ScanMode) {
        currentScanMode = mode
        if (mode == ScanMode.BATCH && _batchSession.value == null) {
            _batchSession.value = BatchScanSession(id = UUID.randomUUID().toString(), mode = mode)
        }
    }

    /** Toggle camera flash on/off. */
    fun toggleFlash() {
        val newState = !_isFlashOn.value
        _isFlashOn.value = newState
        _cameraConfig.value = _cameraConfig.value.copy(
            flashMode = if (newState) FlashMode.ON else FlashMode.OFF
        )
    }

    /** Reset scanner to Preview state, clearing any in-progress capture. */
    fun resetScanner() {
        _uiState.value = ScannerUiState.Preview()
        _processingProgress.value = 0
    }

    /**
     * Called when the camera captures a new image.
     * Runs edge detection then transitions to Review state.
     */
    fun onImageCaptured(context: Context, bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.value = ScannerUiState.Processing(stage = "Detecting edges...", progressPercent = 10)
            _processingProgress.value = 10

            try {
                val edgeResult = detectEdgesUseCase(bitmap)
                _processingProgress.value = 50
                _uiState.value = ScannerUiState.Processing(stage = "Processing...", progressPercent = 50)

                val pageId = UUID.randomUUID().toString()
                val page = ScannedPage(
                    id = pageId,
                    index = (_batchSession.value?.pages?.size ?: 0),
                    mode = currentScanMode
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

    /**
     * Run edge detection on a downscaled preview frame and return
     * the result via callback (avoids StateFlow overhead per frame).
     */
    fun previewEdgeDetection(bitmap: Bitmap, callback: (EdgeDetectionResult) -> Unit) {
        viewModelScope.launch {
            try {
                val result = detectEdgesUseCase(bitmap)
                callback(result)
            } catch (_: Exception) {
                // Silently skip failed preview frames — non-blocking
            }
        }
    }

    /**
     * In batch mode: accept current page and continue scanning.
     */
    fun addToBatchAndContinue() {
        val reviewState = _uiState.value as? ScannerUiState.Review ?: return
        val session = _batchSession.value ?: BatchScanSession(
            id = UUID.randomUUID().toString(),
            mode = ScanMode.BATCH
        )
        _batchSession.value = session.copy(pages = session.pages + reviewState.page)
        resetScanner()
    }

    /**
     * In batch mode: finish the session and return to caller.
     */
    fun completeBatch() {
        val session = _batchSession.value
        if (session != null && session.pages.isNotEmpty()) {
            _uiState.value = ScannerUiState.BatchReview(session = session)
        } else {
            // Single page — wrap in a session
            val reviewState = _uiState.value as? ScannerUiState.Review
            val page = reviewState?.page
            val finalSession = BatchScanSession(
                id = UUID.randomUUID().toString(),
                pages = if (page != null) listOf(page) else emptyList(),
                mode = currentScanMode
            )
            _uiState.value = ScannerUiState.BatchReview(session = finalSession)
        }
    }

    fun deletePage(pageId: String) {
        viewModelScope.launch {
            val page = _batchSession.value?.pages?.find { it.id == pageId }
                ?: ScannedPage(id = pageId, index = 0)
            deleteScanPageUseCase(page)
            _batchSession.value?.let { session ->
                _batchSession.value = session.copy(
                    pages = session.pages.filter { it.id != pageId }
                )
            }
        }
    }
}
