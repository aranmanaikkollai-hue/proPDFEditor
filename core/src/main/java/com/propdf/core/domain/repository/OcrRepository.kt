package com.propdf.core.domain.repository

import android.graphics.Bitmap
import com.propdf.core.domain.result.AppResult

interface OcrRepository {
    suspend fun recognizeText(bitmap: Bitmap): AppResult<String>
    suspend fun recognizeTextFromPdfPage(pdfPageBitmap: Bitmap): AppResult<String>
    fun isLanguageSupported(languageCode: String): Boolean
    fun getSupportedLanguages(): List<String>
}
