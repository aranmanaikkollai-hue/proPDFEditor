package com.propdfeditor.scanner.domain.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import com.propdfeditor.scanner.domain.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for scanner operations.
 * Clean Architecture: Domain defines the contract, Data implements it.
 */
interface ScannerRepository {

    /**
     * Detect document edges in a bitmap using OpenCV.
     * @return EdgeDetectionResult with 4 corners and confidence score.
     */
    suspend fun detectEdges(bitmap: Bitmap): EdgeDetectionResult

    /**
     * Apply perspective correction using detected corners.
     * @param bitmap Source image
     * @param corners 4 detected corners
     * @return Corrected bitmap
     */
    suspend fun correctPerspective(bitmap: Bitmap, corners: List<PointF>): Bitmap

    /**
     * Auto-crop to document boundaries with padding removal.
     */
    suspend fun autoCrop(bitmap: Bitmap, corners: List<PointF>): Bitmap

    /**
     * Remove shadows using adaptive thresholding and inpainting.
     */
    suspend fun removeShadows(bitmap: Bitmap): Bitmap

    /**
     * Apply receipt-specific enhancement (high contrast, narrow crop).
     */
    suspend fun enhanceReceipt(bitmap: Bitmap): Bitmap

    /**
     * Apply whiteboard-specific enhancement (glare reduction, contrast).
     */
    suspend fun enhanceWhiteboard(bitmap: Bitmap): Bitmap

    /**
     * General contrast and clarity enhancement.
     */
    suspend fun enhanceContrast(bitmap: Bitmap, params: EnhancementParams): Bitmap

    /**
     * Generate thumbnail for gallery preview.
     */
    suspend fun generateThumbnail(bitmap: Bitmap, maxDimension: Int = 256): Bitmap

    /**
     * Save processed bitmap to app-scoped storage.
     * @return URI of saved file
     */
    suspend fun saveProcessedImage(context: Context, bitmap: Bitmap, pageId: String): Uri

    /**
     * Delete a scanned page and its associated files.
     */
    suspend fun deletePage(page: ScannedPage): Boolean

    /**
     * Process a captured image through the full pipeline for a given scan mode.
     * Emits progress through the returned Flow.
     */
    fun processImage(
        context: Context,
        bitmap: Bitmap,
        mode: ScanMode,
        pageId: String
    ): Flow<ProcessingProgress>

    /**
     * Batch process multiple images with memory-optimized queue.
     */
    fun batchProcess(
        context: Context,
        bitmaps: List<Bitmap>,
        mode: ScanMode
    ): Flow<BatchProcessingProgress>

    /**
     * Check if device has sufficient memory for processing.
     */
    fun hasSufficientMemory(): Boolean

    /**
     * Get recommended image size based on available RAM.
     */
    fun getRecommendedImageSize(): Size
}

/**
 * Progress emission for single image processing.
 */
data class ProcessingProgress(
    val stage: ProcessingState,
    val progressPercent: Int,
    val resultUri: Uri? = null,
    val thumbnailUri: Uri? = null
)

/**
 * Progress emission for batch processing.
 */
data class BatchProcessingProgress(
    val currentIndex: Int,
    val totalCount: Int,
    val currentStage: ProcessingState,
    val completedPages: List<ScannedPage>,
    val failedPages: List<String>
)
