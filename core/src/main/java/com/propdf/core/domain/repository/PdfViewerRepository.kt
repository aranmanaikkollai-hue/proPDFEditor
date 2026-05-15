package com.propdf.core.domain.repository

import android.graphics.Bitmap
import com.propdf.core.domain.result.AppResult
import java.io.File

interface PdfViewerRepository {
    fun getPageCount(file: File): AppResult<Int>
    fun renderPage(file: File, pageIndex: Int, width: Int): AppResult<Bitmap>
    fun getPageText(file: File, pageIndex: Int): AppResult<String>
    fun getPageSize(file: File, pageIndex: Int): AppResult<Pair<Int, Int>>
    fun clearCache()
}
