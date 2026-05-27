package com.propdf.core.domain.repository

import com.propdf.core.domain.model.*
import com.propdf.core.domain.result.AppResult
import java.io.File

interface PdfOperationsRepository {
    suspend fun merge(request: MergeRequest, outputFile: File): AppResult<File>
    suspend fun split(request: SplitRequest): AppResult<List<File>>
    suspend fun compress(inputFile: File, outputFile: File, config: CompressConfig): AppResult<File>
    suspend fun encrypt(inputFile: File, outputFile: File, config: SecurityConfig): AppResult<File>
    suspend fun decrypt(inputFile: File, outputFile: File, password: String): AppResult<File>
    suspend fun addWatermark(inputFile: File, outputFile: File, config: WatermarkConfig): AppResult<File>
    suspend fun deletePages(inputFile: File, outputFile: File, pages: List<Int>): AppResult<File>
    suspend fun rotatePages(inputFile: File, outputFile: File, rotations: Map<Int, Int>): AppResult<File>
    suspend fun addPageNumbers(inputFile: File, outputFile: File, config: PageNumberConfig): AppResult<File>
    suspend fun addHeaderFooter(inputFile: File, outputFile: File, config: HeaderFooterConfig): AppResult<File>
    suspend fun imagesToPdf(imageFiles: List<File>, outputFile: File): AppResult<File>
    suspend fun insertImageOnPage(inputFile: File, imageFile: File, pageNum: Int, x: Float, y: Float): AppResult<File>
    suspend fun reshapePageSize(inputFile: File, outputFile: File, widthPt: Float, heightPt: Float): AppResult<File>
    suspend fun saveAnnotations(
        inputFile: File,
        outputFile: File,
        pageAnnotations: Map<Int, List<AnnotationStroke>>,
        pageTextAnnotations: Map<Int, List<AnnotationText>> = emptyMap()
    ): AppResult<File>
}
