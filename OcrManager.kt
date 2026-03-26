package com.propdf.editor.data.repository

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OcrManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun initOcr(language: String = "eng"): Result<Unit> = Result.success(Unit)
    suspend fun recognizeText(bitmap: Bitmap): Result<String> = Result.success("OCR coming soon")
    fun release() {}
}
