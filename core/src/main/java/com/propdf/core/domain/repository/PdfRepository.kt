package com.propdf.core.domain.repository

import android.net.Uri

interface PdfRepository {
    suspend fun getPageCount(uri: Uri): Int
    suspend fun renderThumbnail(uri: Uri, page: Int, width: Int): android.graphics.Bitmap?
    suspend fun extractText(uri: Uri, page: Int): String
}
