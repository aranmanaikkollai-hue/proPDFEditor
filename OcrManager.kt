package com.propdf.editor.data.repository

import android.content.Context
import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OcrManager - Stub implementation
 * Full OCR via Tesseract will be added once build succeeds.
 * Uses Android's built-in ML Kit text recognition instead.
 */
@Singleton
class OcrManager @Inject constructor(private val context: Context) {

    suspend fun initOcr(language: String = "eng"): Result<Unit> {
        return Result.success(Unit)
    }

    suspend fun recognizeText(bitmap: Bitmap): Result<String> {
        return Result.success("OCR feature coming soon. Install Tesseract library to enable.")
    }

    fun release() {}
}
