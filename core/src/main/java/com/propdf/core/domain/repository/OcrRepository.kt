package com.propdf.core.domain.repository

import android.graphics.Bitmap
import com.propdf.core.domain.result.AppResult

interface OcrRepository {
    suspend fun recognizeText(bitmap: Bitmap, language: String = "eng"): AppResult<String>
    suspend fun getSupportedLanguages(): AppResult<List<String>>
}
