package com.propdf.core.domain.repository

import android.graphics.Bitmap
import android.net.Uri
import com.propdf.core.domain.model.*
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for all PDF editing operations.
 * Implementations handle the actual PDF manipulation using iTextPDF and PDFBox.
 */
interface PdfOperationsRepository {

    // ==================== PAGE OPERATIONS ====================
    
    suspend fun deletePages(sourceUri: Uri, pageNumbers: List<Int>): AppResult<Uri>
    
    suspend fun duplicatePages(sourceUri: Uri, pageNumbers: List<Int>, insertAfter: Boolean = true): AppResult<Uri>
    
    suspend fun movePages(sourceUri: Uri, pageNumbers: List<Int>, targetPosition: Int): AppResult<Uri>
    
    suspend fun extractPages(sourceUri: Uri, pageNumbers: List<Int>, outputName: String): AppResult<Uri>
    
    suspend fun rotatePages(sourceUri: Uri, pageNumbers: List<Int>, degrees: Int): AppResult<Uri>
    
    suspend fun cropPages(sourceUri: Uri, pageNumbers: List<Int>, config: CropConfig): AppResult<Uri>
    
    suspend fun resizePages(sourceUri: Uri, pageNumbers: List<Int>, config: ResizeConfig): AppResult<Uri>
    
    suspend fun mirrorPages(sourceUri: Uri, pageNumbers: List<Int>, horizontal: Boolean = true): AppResult<Uri>

    // ==================== INSERT OPERATIONS ====================
    
    suspend fun insertBlankPage(sourceUri: Uri, position: Int, width: Float, height: Float): AppResult<Uri>
    
    suspend fun insertImagePage(sourceUri: Uri, position: Int, config: ImageInsertionConfig): AppResult<Uri>
    
    suspend fun insertPdfPages(sourceUri: Uri, insertUri: Uri, position: Int, sourcePages: List<Int> = emptyList()): AppResult<Uri>

    // ==================== SPLIT & MERGE ====================
    
    suspend fun splitBySize(sourceUri: Uri, maxSizeMB: Int, outputPrefix: String): AppResult<List<Uri>>
    
    suspend fun splitByBookmark(sourceUri: Uri, outputPrefix: String): AppResult<List<Uri>>
    
    suspend fun splitEveryNPages(sourceUri: Uri, n: Int, outputPrefix: String): AppResult<List<Uri>>
    
    suspend fun mergePdfs(config: MergeConfig): AppResult<Uri>
    
    suspend fun combineImagesToPdf(imageUris: List<Uri>, outputName: String, config: ImageInsertionConfig): AppResult<Uri>

    // ==================== DOCUMENT ENHANCEMENT ====================
    
    suspend fun addPageNumbers(sourceUri: Uri, config: PageNumberConfig): AppResult<Uri>
    
    suspend fun addHeaderFooter(sourceUri: Uri, config: HeaderFooterConfig): AppResult<Uri>
    
    suspend fun addWatermark(sourceUri: Uri, config: WatermarkConfig): AppResult<Uri>
    
    suspend fun addBackground(sourceUri: Uri, config: BackgroundConfig): AppResult<Uri>

    // ==================== COMPRESSION & OPTIMIZATION ====================
    
    suspend fun compressPdf(sourceUri: Uri, config: CompressConfig): AppResult<Uri>
    
    suspend fun optimizePdf(sourceUri: Uri, aggressive: Boolean = false): AppResult<Uri>

    // ==================== UTILITY ====================
    
    suspend fun getPageCount(uri: Uri): AppResult<Int>
    
    suspend fun getPageThumbnails(uri: Uri, pageNumbers: List<Int>): AppResult<List<Bitmap>>
    
    suspend fun getPageInfo(uri: Uri, pageNumber: Int): AppResult<PdfPage>
    
    suspend fun renderPageToBitmap(uri: Uri, pageNumber: Int, width: Int): AppResult<Bitmap>
    
    fun observeOperationProgress(): Flow<OperationProgress?>
}
