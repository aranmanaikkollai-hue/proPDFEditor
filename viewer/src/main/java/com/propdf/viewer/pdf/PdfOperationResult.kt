package com.propdf.viewer.pdf

import java.io.File

/**
 * Sealed class representing the result of a PDF operation.
 * Provides type-safe success/failure handling with progress metadata.
 */
sealed class PdfOperationResult {
    abstract val operationId: String

    data class Success(
        override val operationId: String,
        val outputFile: File,
        val pagesProcessed: Int,
        val originalSize: Long,
        val outputSize: Long,
        val durationMs: Long
    ) : PdfOperationResult() {
        val compressionRatio: Float
            get() = if (originalSize > 0) 1f - (outputSize.toFloat() / originalSize) else 0f
    }

    data class Failure(
        override val operationId: String,
        val error: Throwable,
        val pagesProcessed: Int = 0
    ) : PdfOperationResult()

    data class Cancelled(
        override val operationId: String,
        val pagesProcessed: Int = 0
    ) : PdfOperationResult()

    data class InProgress(
        override val operationId: String,
        val currentPage: Int,
        val totalPages: Int,
        val percentComplete: Float
    ) : PdfOperationResult() {
        companion object {
            fun create(operationId: String, current: Int, total: Int): InProgress {
                val percent = if (total > 0) (current.toFloat() / total * 100f) else 0f
                return InProgress(operationId, current, total, percent)
            }
        }
    }
}
