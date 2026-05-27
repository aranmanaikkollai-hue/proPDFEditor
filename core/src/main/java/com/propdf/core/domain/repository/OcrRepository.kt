package com.propdf.core.domain.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.propdf.core.domain.result.AppResult

interface OcrRepository {
    suspend fun recognizeText(bitmap: Bitmap, language: String = "eng"): AppResult<String>
    suspend fun recognizeAllPages(bitmaps: List<Bitmap>): AppResult<String>
    suspend fun findPagesContaining(bitmaps: List<Bitmap>, query: String): AppResult<List<Int>>
    suspend fun recognizeFromUri(context: Context, uri: Uri): AppResult<String>
    suspend fun getSupportedLanguages(): AppResult<List<String>>
    fun release()
}
