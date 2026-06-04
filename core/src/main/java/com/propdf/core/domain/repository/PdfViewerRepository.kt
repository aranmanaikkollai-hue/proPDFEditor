package com.propdf.core.domain.repository

import com.propdf.core.domain.result.AppResult
import java.io.File

/**
 * Repository for PDF viewer operations.
 */
interface PdfViewerRepository {
    suspend fun getPageText(file: File, pageIndex: Int): AppResult<String>
}
