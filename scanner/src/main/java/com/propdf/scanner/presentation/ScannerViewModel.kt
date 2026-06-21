package com.propdfeditor.scanner.presentation

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdfeditor.scanner.domain.model.*
import com.propdfeditor.scanner.domain.repository.ProcessingProgress
import com.propdfeditor.scanner.domain.usecase.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject

/**
 * Scanner ViewModel with StateFlow for reactive UI.
 * Handles camera state, processing state, and batch operations.
 *
 * Memory management:
 * - Bitmap references are cleared after processing
 * - Uses viewModelScope for lifecycle-aware coroutines
 * - Cancels processing on ViewModel clear
 */
@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val detectEdgesUseCase: DetectEdgesUseCase,
    private val processScanUseCase: ProcessScanUseCase,
    private val batchScanUseCase: BatchScanUseCase,
    private val deleteScanPageUseCase: DeleteScanPageUseCase,
    private val generateThumbnailUseCase: GenerateThumbnailUseCase
) : ViewModel() {

    // ===== UI State =====
    private val _uiState = MutableStateFlow<ScannerUiState>(ScannerUiState.Idle)
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    // ===== Camera State =====
    private val _cameraConfig = MutableStateFlow(ScannerCameraConfig())
    val cameraConfig: StateFlow<ScannerCameraConfig> = _cameraConfig.asStateFlow()

    private val _isFlashOn = MutableStateFlow(false)
    val isFlashOn: StateFlow<Boolean> = _isFlashOn.asStateFlow()

    private val _currentScanMode = MutableStateFlow(ScanMode.AUTO)
    val currentScanMode: StateFlow<ScanMode> = _currentScanMode.asStateFlow()

    // ===== Batch State =====
    private val _batchSession = MutableStateFlow<BatchScanSession?>(null)
    val batchSession: StateFlow<BatchScanSession?> = _batchSession.asStateFlow()

    // ===== Processing State =====
    private val _processingProgress = MutableStateFlow(0)
    val processingProgress: StateFlow<Int> = _processingProgress.asStateFlow()

    private val _currentProcessingStage = MutableStateFlow("")
    val currentProcessingStage: StateFlow<String> = _currentProcessingStage.asStateFlow()

    // ===== Review State =====
    private val _lastScannedPage = MutableStateFlow<ScannedPage?>(null)
    val lastScannedPage: StateFlow<ScannedPage?> = _lastScannedPage.asStateFlow()

    // ===== Error State =====
    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    // Active processing job for cancellation
    private var processingJob: Job? = null

    /**
     * Initialize scanner with default configuration.
     */
    fun initializeScanner() {
        _uiState.value = ScannerUiState.Preview(
            isFlashOn = _isFlashOn.value,
            isAutoCapture = _cameraConfig.value.enableAutoCapture
        )
    }

    /**
     * Handle image capture from CameraX.
     * Triggers edge detection and full processing pipeline.
     */
    fun onImageCaptured(context: Context, bitmap: Bitmap) {
        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            val pageId = UUID.randomUUID().toString()
            val mode = _currentScanMode.value

            _uiState.value = ScannerUiState.Processing(pageId, "Detecting edges...")
            _currentProcessingStage.value = "Detecting document edges"

            processScanUseCase(context, bitmap, mode, pageId)
                .onEach { progress ->
                    _processingProgress.value = progress.progressPercent
                    _currentProcessingStage.value = when (progress.stage) {
                        ProcessingState.DETECTING -> "Detecting document edges"
                        ProcessingState.CORRECTING -> "Correcting perspective"
                        ProcessingState.ENHANCING -> "Enhancing image"
                        ProcessingState.COMPLETE -> "Complete"
                        ProcessingState.FAILED -> "Failed"
                        else -> "Processing..."
                    }
                }
                .catch { e ->
                    _uiState.value = ScannerUiState.Error(
                        message = "Processing failed: ${e.message}",
                        recoverable = true
                    )
                    _errorMessage.emit("Failed to process scan: ${e.message}")
                }
                .collect { progress ->
                    when (progress.stage) {
                        ProcessingState.COMPLETE -> {
                            val page = ScannedPage(
                                id = pageId,
                                originalUri = Uri.EMPTY,
                                processedUri = progress.resultUri,
                                thumbnailUri = progress.thumbnailUri,
                                processingState = ProcessingState.COMPLETE,
                                scanMode = mode,
                                width = bitmap.width,
                                height = bitmap.height
                            )
                            _lastScannedPage.value = page
                            _uiState.value = ScannerUiState.Review(page)

                            // Add to batch session if active
                            _batchSession.value?.let { session ->
                                val updatedPages = session.pages + page
                                _batchSession.value = session.copy(
                                    pages = updatedPages,
                                    currentPageIndex = updatedPages.size - 1
                                )
                            }
                        }
                        ProcessingState.FAILED -> {
                            _uiState.value = ScannerUiState.Error(
                                message = "Image processing failed",
                                recoverable = true
                            )
                        }
                        else -> {
                            _uiState.value = ScannerUiState.Processing(pageId, _currentProcessingStage.value)
                        }
                    }
                }
        }
    }

    /**
     * Quick edge detection for real-time preview overlay.
     * Lightweight operation for UI feedback only.
     */
    fun previewEdgeDetection(bitmap: Bitmap, onResult: (EdgeDetectionResult) -> Unit) {
        viewModelScope.launch {
            try {
                val result = detectEdgesUseCase(bitmap)
                onResult(result)
            } catch (e: Exception) {
                // Silently fail for preview - don't disrupt UX
            }
        }
    }

    /**
     * Add current page to batch and return to camera.
     */
    fun addToBatchAndContinue() {
        _lastScannedPage.value?.let { page ->
            val currentSession = _batchSession.value
            if (currentSession == null) {
                _batchSession.value = BatchScanSession(
                    id = UUID.randomUUID().toString(),
                    pages = listOf(page)
                )
            } else {
                _batchSession.value = currentSession.copy(
                    pages = currentSession.pages + page
                )
            }
        }
        _uiState.value = ScannerUiState.Preview(
            isFlashOn = _isFlashOn.value,
            isAutoCapture = _cameraConfig.value.enableAutoCapture
        )
        _lastScannedPage.value = null
    }

    /**
     * Complete batch scanning session.
     */
    fun completeBatch() {
        _batchSession.value?.let { session ->
            _uiState.value = ScannerUiState.BatchReview(
                session.copy(isComplete = true)
            )
        }
    }

    /**
     * Process all pending batch images.
     */
    fun processBatch(context: Context, bitmaps: List<Bitmap>) {
        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            _uiState.value = ScannerUiState.Processing("batch", "Processing batch...")

            batchScanUseCase(context, bitmaps, _currentScanMode.value)
                .catch { e ->
                    _uiState.value = ScannerUiState.Error(
                        message = "Batch processing failed: ${e.message}",
                        recoverable = false
                    )
                }
                .collect { progress ->
                    _processingProgress.value =
                        ((progress.currentIndex.toFloat() / progress.totalCount) * 100).toInt()
                    _currentProcessingStage.value =
                        "Processing ${progress.currentIndex + 1}/${progress.totalCount}"

                    if (progress.currentIndex == progress.totalCount) {
                        val session = BatchScanSession(
                            id = UUID.randomUUID().toString(),
                            pages = progress.completedPages,
                            isComplete = true
                        )
                        _batchSession.value = session
                        _uiState.value = ScannerUiState.BatchReview(session)
                    }
                }
        }
    }

    /**
     * Delete a page from the current session.
     */
    fun deletePage(page: ScannedPage) {
        viewModelScope.launch {
            deleteScanPageUseCase(page)

            _batchSession.value?.let { session ->
                val updated = session.pages.filter { it.id != page.id }
                _batchSession.value = session.copy(pages = updated)
            }

            if (_lastScannedPage.value?.id == page.id) {
                _lastScannedPage.value = null
            }
        }
    }

    /**
     * Set scan mode (Document, Receipt, Whiteboard, etc.)
     */
    fun setScanMode(mode: ScanMode) {
        _currentScanMode.value = mode
    }

    /**
     * Toggle flash state.
     */
    fun toggleFlash() {
        _isFlashOn.value = !_isFlashOn.value
        _cameraConfig.value = _cameraConfig.value.copy(
            flashMode = if (_isFlashOn.value) 1 else 0
        )
    }

    /**
     * Toggle auto-capture.
     */
    fun toggleAutoCapture() {
        _cameraConfig.value = _cameraConfig.value.copy(
            enableAutoCapture = !_cameraConfig.value.enableAutoCapture
        )
    }

    /**
     * Retry failed processing.
     */
    fun retryProcessing(context: Context, bitmap: Bitmap) {
        onImageCaptured(context, bitmap)
    }

    /**
     * Reset scanner to initial state.
     */
    fun resetScanner() {
        processingJob?.cancel()
        _uiState.value = ScannerUiState.Idle
        _lastScannedPage.value = null
        _processingProgress.value = 0
        _currentProcessingStage.value = ""
    }

    /**
     * Clear batch session.
     */
    fun clearBatch() {
        _batchSession.value?.pages?.forEach { page ->
            viewModelScope.launch {
                deleteScanPageUseCase(page)
            }
        }
        _batchSession.value = null
    }

    override fun onCleared() {
        super.onCleared()
        processingJob?.cancel()
        // Clear bitmap references
        _lastScannedPage.value = null
    }
}
