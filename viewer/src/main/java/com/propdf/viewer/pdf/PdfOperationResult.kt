package com.propdf.viewer.pdf

import java.io.File

sealed class PdfOperationResult(
    open val operationId: String
) {
    data class InProgress(
        override val operationId: String,
        val current: Int,
        val total: Int
    ) : PdfOperationResult(operationId) {
        val percentComplete: Float = if (total > 0) (current.toFloat() / total) * 100 else 0f

        companion object {
            fun create(operationId: String, current: Int, total: Int) =
                InProgress(operationId, current, total)
        }
    }

    data class Success(
        override val operationId: String,
        val outputFile: File,
        val pageCount: Int,
        val originalSize: Long,
        val outputSize: Long,
        val compressionRatio: Float
    ) : PdfOperationResult(operationId)

    data class Failure(
        override val operationId: String,
        val error: Throwable
    ) : PdfOperationResult(operationId)

    data class Cancelled(
        override val operationId: String,
        val pagesCompleted: Int
    ) : PdfOperationResult(operationId)
}
