package com.propdf.core.domain.repository

import android.graphics.Bitmap
import android.net.Uri
import com.propdf.core.domain.result.AppResult
import java.io.File

/**
 * Repository interface for PDF viewing operations.
 * Handles URI resolution, page rendering, and text extraction.
 */
interface PdfViewerRepository {
    /** Copy a content URI to app's private cache for rendering. */
    suspend fun copyUriToCache(uri: Uri): AppResult<File>

    /** Get total page count of a PDF file. */
    suspend fun getPageCount(file: File): AppResult<Int>

    /** Render a specific page as a Bitmap. */
    suspend fun renderPage(file: File, pageIndex: Int, screenWidth: Int): AppResult<Bitmap>

    /** Extract text content from a specific page. */
    suspend fun getPageText(file: File, pageIndex: Int): AppResult<String>

    /** Preload pages around anchor page for smoother scrolling. */
    suspend fun preloadPages(file: File, anchorPage: Int, screenWidth: Int): AppResult<Unit>

    /** Clear all cached PDF files. */
    fun clearCache()
}
