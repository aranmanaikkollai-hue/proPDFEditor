package com.propdf.core.domain.repository

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.propdf.core.domain.model.HandwritingResult
import com.propdf.core.domain.model.OcrConfig
import com.propdf.core.domain.model.OcrLanguage
import com.propdf.core.domain.model.OcrPageResult
import com.propdf.core.domain.model.OcrPreprocessConfig
import com.propdf.core.domain.model.OcrRecord
import com.propdf.core.domain.model.OcrTable
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow

interface OcrRepository {
    // Legacy single-shot OCR record API (kept for existing callers).
    suspend fun performOcr(uri: Uri, language: String): Result<OcrRecord>
    suspend fun getRecentOcr(limit: Int): List<OcrRecord>
    suspend fun searchOcr(query: String): List<OcrRecord>

    // Batch OCR pipeline API used by OcrViewModel / OcrWorker.
    suspend fun recognizeImage(bitmap: Bitmap, config: OcrConfig): AppResult<OcrPageResult>
    suspend fun recognizeImageUri(uri: Uri, config: OcrConfig): AppResult<OcrPageResult>
    fun recognizeBatch(uris: List<Uri>, config: OcrConfig): Flow<AppResult<OcrPageResult>>

    suspend fun correctText(text: String, language: OcrLanguage): AppResult<String>

    suspend fun detectHandwriting(bitmap: Bitmap): AppResult<HandwritingResult>
    suspend fun detectTables(bitmap: Bitmap, config: OcrConfig): AppResult<List<OcrTable>>

    fun downloadModel(language: OcrLanguage): Flow<AppResult<Int>>
    suspend fun isModelDownloaded(language: OcrLanguage): Boolean
    suspend fun deleteModel(language: OcrLanguage): AppResult<Unit>

    suspend fun exportToPdf(results: List<OcrPageResult>, outputUri: Uri): AppResult<Uri>
    suspend fun exportToTxt(results: List<OcrPageResult>, outputUri: Uri): AppResult<Uri>
    suspend fun exportToDocx(results: List<OcrPageResult>, outputUri: Uri): AppResult<Uri>

    suspend fun preprocessImage(bitmap: Bitmap, config: OcrPreprocessConfig): AppResult<Bitmap>
    suspend fun cropImage(bitmap: Bitmap, cropRect: Rect): AppResult<Bitmap>

    suspend fun searchInText(text: String, query: String, caseSensitive: Boolean = false): AppResult<List<IntRange>>
}
