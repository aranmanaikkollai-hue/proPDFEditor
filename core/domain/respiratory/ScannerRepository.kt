package com.propdf.core.domain.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.propdf.core.domain.model.ExportConfig
import com.propdf.core.domain.result.AppResult

interface ScannerRepository {
    suspend fun processCapturedImage(bitmap: Bitmap, colorMode: String): AppResult<Bitmap>
    suspend fun applyFilter(bitmap: Bitmap, filterType: String): AppResult<Bitmap>
    suspend fun applyBrightness(bitmap: Bitmap, delta: Int): AppResult<Bitmap>
    suspend fun rotate(bitmap: Bitmap, degrees: Float): AppResult<Bitmap>
    suspend fun saveAsPdf(
        context: Context,
        pages: List<Bitmap>,
        config: ExportConfig
    ): AppResult<Uri>
    suspend fun saveAsJpegs(
        context: Context,
        pages: List<Bitmap>,
        quality: Int
    ): AppResult<List<Uri>>
    fun release()
}
