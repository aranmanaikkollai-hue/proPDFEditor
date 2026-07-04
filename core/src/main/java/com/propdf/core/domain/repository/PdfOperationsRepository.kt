package com.propdf.core.domain.repository

import android.graphics.Bitmap
import android.net.Uri
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
    suspend fun rotatePages(inputFile: File, outputFile: File, rotations: Map<Int, Float>): AppResult<File>
    suspend fun addPageNumbers(inputFile: File, outputFile: File, config: PageNumberConfig): AppResult<File>
    suspend fun addHeaderFooter(inputFile: File, outputFile: File, config: HeaderFooterConfig): AppResult<File>
    suspend fun imagesToPdf(imageFiles: List<File>, outputFile: File): AppResult<File>
    suspend fun insertImageOnPage(inputFile: File, outputFile: File, config: ImageInsertionConfig): AppResult<File>
    suspend fun reshapePageSize(inputFile: File, outputFile: File, widthPt: Float, heightPt: Float): AppResult<File>
    suspend fun saveAnnotations(
        inputFile: File,
        outputFile: File,
        pageAnnotations: Map<Int, Pair<List<AnnotationStroke>, Float>>,
        pageTextAnnotations: Map<Int, Pair<List<AnnotationText>, Float>> = emptyMap()
    ): AppResult<File>
    suspend fun extractPagesAsImages(inputFile: File, pages: List<Int>? = null): AppResult<List<Bitmap>>
    suspend fun renderPageToBitmap(inputFile: File, pageNum: Int, width: Int? = null): AppResult<Bitmap>

    // ==================== Uri-based page operations (used by PdfOperationWorker) ====================
    // These resolve the source Uri to a local File internally and delegate to (or share the
    // same iText7 techniques as) the File-based operations above.

    suspend fun deletePages(sourceUri: Uri, pages: List<Int>): AppResult<File>
    suspend fun duplicatePages(sourceUri: Uri, pages: List<Int>): AppResult<File>
    suspend fun movePages(sourceUri: Uri, pages: List<Int>, target: Int): AppResult<File>
    suspend fun extractPages(sourceUri: Uri, pages: List<Int>, outputName: String): AppResult<File>
    suspend fun rotatePages(sourceUri: Uri, pages: List<Int>, degrees: Int): AppResult<File>
    suspend fun cropPages(sourceUri: Uri, pages: List<Int>, config: CropConfig): AppResult<File>
    suspend fun resizePages(sourceUri: Uri, pages: List<Int>, config: ResizeConfig): AppResult<File>
    suspend fun mirrorPages(sourceUri: Uri, pages: List<Int>, horizontal: Boolean): AppResult<File>
    suspend fun insertBlankPage(sourceUri: Uri, position: Int, width: Float, height: Float): AppResult<File>
    suspend fun splitBySize(sourceUri: Uri, maxSizeMb: Int, outputPrefix: String): AppResult<List<File>>
    suspend fun splitByBookmark(sourceUri: Uri, outputPrefix: String): AppResult<List<File>>
    suspend fun splitEveryNPages(sourceUri: Uri, n: Int, outputPrefix: String): AppResult<List<File>>
    suspend fun addPageNumbers(sourceUri: Uri, config: PageNumberConfig): AppResult<File>
    suspend fun addHeaderFooter(sourceUri: Uri, config: HeaderFooterConfig): AppResult<File>
    suspend fun addWatermark(sourceUri: Uri, config: WatermarkConfig): AppResult<File>
    suspend fun addBackground(sourceUri: Uri, config: BackgroundConfig): AppResult<File>
    suspend fun compressPdf(sourceUri: Uri, config: CompressConfig): AppResult<File>
    suspend fun optimizePdf(sourceUri: Uri, aggressive: Boolean): AppResult<File>

    // ==================== Uri-based tool-screen operations ====================
    suspend fun insertImagePage(sourceUri: Uri, position: Int, config: ImageInsertionConfig): AppResult<File>
    suspend fun combineImagesToPdf(images: List<Uri>, outputName: String, config: ImageInsertionConfig): AppResult<File>
}
