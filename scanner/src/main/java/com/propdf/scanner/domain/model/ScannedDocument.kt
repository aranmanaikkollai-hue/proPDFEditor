package com.propdfeditor.scanner.domain.model

import android.graphics.RectF
import android.net.Uri

/**
 * Represents a single scanned page with all processing metadata.
 * Immutable data class for coroutine-safe operations.
 */
data class ScannedPage(
    val id: String,
    val originalUri: Uri,
    val processedUri: Uri? = null,
    val thumbnailUri: Uri? = null,
    val detectedCorners: List<PointF> = emptyList(),
    val scanMode: ScanMode = ScanMode.AUTO,
    val processingState: ProcessingState = ProcessingState.CAPTURED,
    val createdAt: Long = System.currentTimeMillis(),
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0
) {
    val isProcessed: Boolean get() = processingState == ProcessingState.COMPLETE
    val hasDetectedCorners: Boolean get() = detectedCorners.size == 4
}

/**
 * Floating point point for sub-pixel edge detection accuracy.
 */
data class PointF(
    val x: Float,
    val y: Float
)

/**
 * Scan modes with optimized processing pipelines.
 */
enum class ScanMode {
    AUTO,           // Automatic detection and enhancement
    DOCUMENT,       // Standard document: edge detect + perspective + auto-crop
    RECEIPT,        // Receipt mode: high contrast + shadow removal + narrow crop
    WHITEBOARD,     // Whiteboard mode: glare reduction + contrast boost + color correction
    PHOTO,          // Photo mode: minimal processing, just perspective correction
    ID_CARD,        // ID card mode: aspect ratio lock + auto-crop
    BATCH           // Batch mode: fast capture, deferred processing
}

/**
 * Processing lifecycle states for reactive UI.
 */
enum class ProcessingState {
    CAPTURED,       // Raw image captured
    DETECTING,      // Edge detection in progress
    CORRECTING,     // Perspective correction in progress
    ENHANCING,      // Image enhancement in progress
    COMPLETE,       // All processing done
    FAILED          // Processing failed
}

/**
 * Batch scan session for multi-page documents.
 */
data class BatchScanSession(
    val id: String,
    val pages: List<ScannedPage> = emptyList(),
    val currentPageIndex: Int = 0,
    val isComplete: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Edge detection result with confidence score.
 */
data class EdgeDetectionResult(
    val corners: List<PointF>,
    val confidence: Float,
    val boundingRect: RectF
) {
    val hasDetectedCorners: Boolean get() = corners.size == 4
}

/**
 * Image enhancement parameters.
 */
data class EnhancementParams(
    val contrast: Float = 1.0f,
    val brightness: Float = 0.0f,
    val saturation: Float = 1.0f,
    val shadowRemoval: Boolean = false,
    val denoise: Boolean = false,
    val sharpen: Boolean = false,
    val blackAndWhite: Boolean = false
)

/**
 * Scanner UI state for StateFlow emission.
 */
sealed class ScannerUiState {
    data object Idle : ScannerUiState()
    data class Preview(val isFlashOn: Boolean, val isAutoCapture: Boolean) : ScannerUiState()
    data class Detecting(val progress: Float) : ScannerUiState()
    data class Processing(val pageId: String, val stage: String) : ScannerUiState()
    data class Review(val page: ScannedPage) : ScannerUiState()
    data class BatchReview(val session: BatchScanSession) : ScannerUiState()
    data class Error(val message: String, val recoverable: Boolean) : ScannerUiState()
}

/**
 * Camera configuration optimized for document scanning.
 */
data class ScannerCameraConfig(
    val targetResolution: android.util.Size = android.util.Size(1920, 1080),
    val autoFocusMode: Int = 0,
    val flashMode: Int = 0,
    val enableTapToFocus: Boolean = true,
    val enablePinchToZoom: Boolean = true,
    val enableAutoCapture: Boolean = true,
    val autoCaptureThreshold: Float = 0.85f
)
