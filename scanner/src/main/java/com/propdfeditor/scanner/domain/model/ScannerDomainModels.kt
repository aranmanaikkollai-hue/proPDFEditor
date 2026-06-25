package com.propdfeditor.scanner.domain.model

import android.net.Uri

// ----- Geometry -----

data class PointF(val x: Float, val y: Float)

// ----- Edge Detection -----

data class EdgeDetectionResult(
    val corners: List<PointF>,
    val confidence: Float,
    val detectedRect: android.graphics.RectF? = null
)

// ----- Scan mode -----

enum class ScanMode {
    AUTO,
    DOCUMENT,
    RECEIPT,
    WHITEBOARD,
    BATCH
}

// ----- Scanned page -----

data class ScannedPage(
    val id: String,
    val index: Int,
    val originalUri: Uri? = null,
    val processedUri: Uri? = null,
    val thumbnailUri: Uri? = null,
    val width: Int = 0,
    val height: Int = 0,
    val mode: ScanMode = ScanMode.AUTO
)

// ----- Batch session -----

data class BatchScanSession(
    val id: String,
    val pages: List<ScannedPage> = emptyList(),
    val mode: ScanMode = ScanMode.BATCH
)

// ----- Enhancement -----

data class EnhancementParams(
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val sharpness: Float = 0f
)

// ----- Camera config -----

data class ScannerCameraConfig(
    val enableAutoCapture: Boolean = false,
    val flashMode: FlashMode = FlashMode.AUTO,
    val targetResolutionWidth: Int = 1920,
    val targetResolutionHeight: Int = 1080
)

enum class FlashMode { AUTO, ON, OFF }

// ----- Processing state -----

enum class ProcessingState {
    IDLE,
    DETECTING_EDGES,
    CORRECTING_PERSPECTIVE,
    ENHANCING,
    SAVING,
    GENERATING_THUMBNAIL,
    COMPLETE,
    ERROR
}

// ----- UI State -----

sealed class ScannerUiState {
    object Idle : ScannerUiState()
    data class Preview(val cameraReady: Boolean = true) : ScannerUiState()
    data class Processing(val stage: String, val progressPercent: Int = 0) : ScannerUiState()
    data class Review(val page: ScannedPage) : ScannerUiState()
    data class BatchReview(val session: BatchScanSession) : ScannerUiState()
    data class Error(val message: String, val recoverable: Boolean = true) : ScannerUiState()

    // Legacy states (kept for backward compat with old ScannerViewModel)
    data class EdgesDetected(val result: EdgeDetectionResult) : ScannerUiState()
    data class ScanComplete(val result: Any?) : ScannerUiState()
}
