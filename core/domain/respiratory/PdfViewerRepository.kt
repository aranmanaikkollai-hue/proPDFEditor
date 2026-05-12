package com.propdf.core.domain.repository

import android.graphics.Bitmap
import android.net.Uri
import com.propdf.core.domain.result.AppResult
import java.io.File

interface PdfViewerRepository {
    suspend fun copyUriToCache(uri: Uri): AppResult<File>
    suspend fun getPageCount(file: File): AppResult<Int>
    suspend fun renderPage(file: File, pageIndex: Int, screenWidth: Int): AppResult<Bitmap>
    suspend fun getPageText(file: File, pageIndex: Int): AppResult<String>
    suspend fun preloadPages(file: File, anchorPage: Int, screenWidth: Int): AppResult<Unit>
    fun clearCache()
}
