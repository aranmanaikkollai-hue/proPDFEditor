package com.propdf.core.domain.repository

import android.graphics.Bitmap
import android.net.Uri
import com.propdf.core.domain.model.*
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for all OCR operations.
 * All methods are suspend and return AppResult for consistent error handling.
 */
interface OcrRepository {

    /**
     * Check if the ML Kit model for a language is downloaded and ready.
     */
    suspend fun isModelDownloaded(language: OcrLanguage): Boolean

    /**
     * Download ML Kit model for a language. Returns progress 0-100.
     */
    fun downloadModel(language: OcrLanguage): Flow<AppResult<Int>>

    /**
     * Delete downloaded model to free space.
     */
    suspend fun deleteModel(language: OcrLanguage): AppResult<Unit>

    /**
     * Get list of downloaded models.
     */
    suspend fun getDownloadedModels(): AppResult<List<OcrLanguage>>

    /**
     * Perform OCR on a single image bitmap.
     */
    suspend fun recognizeImage(
        bitmap: Bitmap,
        config: OcrConfig
    ): AppResult<OcrPageResult>

    /**
     * Perform OCR on an image URI.
     */
    suspend fun recognizeImageUri(
        uri: Uri,
        config: OcrConfig
    ): AppResult<OcrPageResult>

    /**
     * Perform batch OCR on multiple image URIs.
     * Emits progress updates.
     */
    fun recognizeBatch(
        uris: List<Uri>,
        config: OcrConfig
    ): Flow<AppResult<OcrPageResult>>

    /**
     * Detect tables in an image.
     */
    suspend fun detectTables(
        bitmap: Bitmap,
        config: OcrConfig
    ): AppResult<List<OcrTable>>

    /**
     * Detect handwriting regions in an image.
     */
    suspend fun detectHandwriting(
        bitmap: Bitmap
    ): AppResult<HandwritingResult>

    /**
     * Correct OCR text using dictionary and context.
     */
    suspend fun correctText(
        text: String,
        language: OcrLanguage
    ): AppResult<String>

    /**
     * Search within OCR text.
     */
    suspend fun searchInText(
        text: String,
        query: String,
        caseSensitive: Boolean = false
    ): AppResult<List<IntRange>>

    /**
     * Export OCR results to various formats.
     */
    suspend fun exportToPdf(
        results: List<OcrPageResult>,
        outputUri: Uri
    ): AppResult<Uri>

    suspend fun exportToTxt(
        results: List<OcrPageResult>,
        outputUri: Uri
    ): AppResult<Uri>

    suspend fun exportToDocx(
        results: List<OcrPageResult>,
        outputUri: Uri
    ): AppResult<Uri>

    /**
     * Preprocess image before OCR (deskew, denoise, perspective correction).
     */
    suspend fun preprocessImage(
        bitmap: Bitmap,
        config: OcrPreprocessConfig
    ): AppResult<Bitmap>

    /**
     * Crop image to specified rect before OCR.
     */
    suspend fun cropImage(
        bitmap: Bitmap,
        rect: android.graphics.Rect
    ): AppResult<Bitmap>
}
