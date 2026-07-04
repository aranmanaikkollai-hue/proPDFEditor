package com.propdf.editor.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import com.itextpdf.kernel.pdf.navigation.PdfExplicitDestination
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.VerticalAlignment
import com.propdf.core.domain.model.*
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class PdfOperationsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PdfOperationsRepository {

    private val _progressFlow = MutableStateFlow<OperationProgress?>(null)
    override fun observeOperationProgress(): StateFlow<OperationProgress?> = _progressFlow.asStateFlow()

    private fun createTempOutputFile(prefix: String = "pdf_op"): File {
        val dir = File(context.cacheDir, "pdf_operations").apply { mkdirs() }
        return File(dir, "${prefix}_${System.currentTimeMillis()}.pdf")
    }

    private fun updateProgress(id: String, current: Int, total: Int, message: String) {
        _progressFlow.value = OperationProgress(id, current, total, message)
    }

    // ==================== PAGE OPERATIONS ====================

    override suspend fun deletePages(sourceUri: Uri, pageNumbers: List<Int>): AppResult<Uri> = 
        withContext(Dispatchers.IO) {
            runCatching {
                require(pageNumbers.isNotEmpty()) { "Page numbers cannot be empty" }
                val outputFile = createTempOutputFile("deleted")
                val pageSet = pageNumbers.toSortedSet()
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                        val total = srcDoc.numberOfPages
                        for (i in 1..total) {
                            if (i !in pageSet) {
                                srcDoc.copyPagesTo(i, i, destDoc, PdfPageFormCopier())
                            }
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to delete pages: ${it.message}")) }
        }

    override suspend fun duplicatePages(sourceUri: Uri, pageNumbers: List<Int>, insertAfter: Boolean): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(pageNumbers.isNotEmpty()) { "Page numbers cannot be empty" }
                val outputFile = createTempOutputFile("duplicated")
                val sortedPages = pageNumbers.sorted().toSet()
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                        val total = srcDoc.numberOfPages
                        for (i in 1..total) {
                            srcDoc.copyPagesTo(i, i, destDoc, PdfPageFormCopier())
                            if (i in sortedPages) {
                                srcDoc.copyPagesTo(i, i, destDoc, PdfPageFormCopier())
                            }
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to duplicate pages: ${it.message}")) }
        }

    override suspend fun movePages(sourceUri: Uri, pageNumbers: List<Int>, targetPosition: Int): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(pageNumbers.isNotEmpty()) { "Page numbers cannot be empty" }
                val outputFile = createTempOutputFile("moved")
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    val total = srcDoc.numberOfPages
                    val pagesToMove = pageNumbers.sorted().distinct().filter { it in 1..total }
                    val remainingPages = (1..total).filter { it !in pagesToMove }
                    
                    val newOrder = mutableListOf<Int>()
                    val insertPos = targetPosition.coerceIn(0, remainingPages.size)
                    
                    newOrder.addAll(remainingPages.take(insertPos))
                    newOrder.addAll(pagesToMove)
                    newOrder.addAll(remainingPages.drop(insertPos))
                    
                    PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                        for (pageNum in newOrder) {
                            srcDoc.copyPagesTo(pageNum, pageNum, destDoc, PdfPageFormCopier())
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to move pages: ${it.message}")) }
        }

    override suspend fun extractPages(sourceUri: Uri, pageNumbers: List<Int>, outputName: String): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(pageNumbers.isNotEmpty()) { "Page numbers cannot be empty" }
                val outputFile = File(context.cacheDir, "pdf_operations/$outputName.pdf").apply {
                    parentFile?.mkdirs()
                }
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                        val sorted = pageNumbers.sorted().distinct()
                        for (pageNum in sorted) {
                            if (pageNum in 1..srcDoc.numberOfPages) {
                                srcDoc.copyPagesTo(pageNum, pageNum, destDoc, PdfPageFormCopier())
                            }
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to extract pages: ${it.message}")) }
        }

    override suspend fun rotatePages(sourceUri: Uri, pageNumbers: List<Int>, degrees: Int): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(pageNumbers.isNotEmpty()) { "Page numbers cannot be empty" }
                val outputFile = createTempOutputFile("rotated")
                val pageSet = pageNumbers.toSet()
                val rotation = ((degrees % 360 + 360) % 360)
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                        val total = srcDoc.numberOfPages
                        for (i in 1..total) {
                            val newPage = srcDoc.getPage(i).copyTo(destDoc)
                            if (i in pageSet) {
                                val currentRotation = newPage.rotation
                                newPage.rotation = (currentRotation + rotation) % 360
                            }
                            destDoc.addPage(newPage)
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to rotate pages: ${it.message}")) }
        }

    override suspend fun cropPages(sourceUri: Uri, pageNumbers: List<Int>, config: CropConfig): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(pageNumbers.isNotEmpty()) { "Page numbers cannot be empty" }
                val outputFile = createTempOutputFile("cropped")
                val pageSet = pageNumbers.toSet()
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                        for (i in 1..srcDoc.numberOfPages) {
                            val page = srcDoc.getPage(i)
                            val newPage = page.copyTo(destDoc)
                            
                            if (i in pageSet) {
                                val mediaBox = page.pageSize
                                val newBox = Rectangle(
                                    mediaBox.left + config.leftMargin,
                                    mediaBox.bottom + config.bottomMargin,
                                    mediaBox.width - config.leftMargin - config.rightMargin,
                                    mediaBox.height - config.topMargin - config.bottomMargin
                                )
                                newPage.mediaBox = newBox
                                newPage.cropBox = newBox
                            }
                            destDoc.addPage(newPage)
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to crop pages: ${it.message}")) }
        }

    override suspend fun resizePages(sourceUri: Uri, pageNumbers: List<Int>, config: ResizeConfig): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(pageNumbers.isNotEmpty()) { "Page numbers cannot be empty" }
                val outputFile = createTempOutputFile("resized")
                val pageSet = pageNumbers.toSet()
                val targetSize = PageSize(config.targetWidth, config.targetHeight)
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                        for (i in 1..srcDoc.numberOfPages) {
                            val page = srcDoc.getPage(i)
                            val newPage = page.copyTo(destDoc)
                            
                            if (i in pageSet) {
                                newPage.setPageSize(targetSize)
                            }
                            destDoc.addPage(newPage)
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to resize pages: ${it.message}")) }
        }

    override suspend fun mirrorPages(sourceUri: Uri, pageNumbers: List<Int>, horizontal: Boolean): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(pageNumbers.isNotEmpty()) { "Page numbers cannot be empty" }
                val outputFile = createTempOutputFile("mirrored")
                val pageSet = pageNumbers.toSet()
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                        for (i in 1..srcDoc.numberOfPages) {
                            val page = srcDoc.getPage(i)
                            val newPage = page.copyTo(destDoc)
                            
                            if (i in pageSet) {
                                val canvas = PdfCanvas(newPage)
                                val size = newPage.pageSize
                                canvas.saveState()
                                
                                if (horizontal) {
                                    canvas.concatMatrix(-1f, 0f, 0f, 1f, size.width, 0f)
                                } else {
                                    canvas.concatMatrix(1f, 0f, 0f, -1f, 0f, size.height)
                                }
                                
                                canvas.restoreState()
                            }
                            destDoc.addPage(newPage)
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to mirror pages: ${it.message}")) }
        }

    // ==================== INSERT OPERATIONS ====================

    override suspend fun insertBlankPage(sourceUri: Uri, position: Int, width: Float, height: Float): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val outputFile = createTempOutputFile("blank_inserted")
                val insertPos = position.coerceIn(1, Int.MAX_VALUE)
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                        val total = srcDoc.numberOfPages
                        
                        for (i in 1..total) {
                            if (i == insertPos) {
                                destDoc.addNewPage(PageSize(width, height))
                            }
                            srcDoc.copyPagesTo(i, i, destDoc, PdfPageFormCopier())
                        }
                        if (insertPos > total) {
                            destDoc.addNewPage(PageSize(width, height))
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to insert blank page: ${it.message}")) }
        }

    override suspend fun insertImagePage(sourceUri: Uri, position: Int, config: ImageInsertionConfig): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val outputFile = createTempOutputFile("image_inserted")
                val bytes = context.contentResolver.openInputStream(config.imageUri)?.use { it.readBytes() }
                    ?: return@withContext AppResult.Error(AppException.FileNotFound("Cannot read image"))
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                        val total = srcDoc.numberOfPages
                        val pageSize = PageSize(config.pageWidth, config.pageHeight)
                        
                        for (i in 1..total) {
                            if (i == position) {
                                insertImageIntoDoc(destDoc, bytes, pageSize, config)
                            }
                            srcDoc.copyPagesTo(i, i, destDoc, PdfPageFormCopier())
                        }
                        if (position > total) {
                            insertImageIntoDoc(destDoc, bytes, pageSize, config)
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to insert image page: ${it.message}")) }
        }

    private fun insertImageIntoDoc(destDoc: PdfDocument, imageBytes: ByteArray, pageSize: PageSize, config: ImageInsertionConfig) {
        val newPage = destDoc.addNewPage(pageSize)
        val imageData = ImageDataFactory.create(imageBytes)
        val image = Image(imageData)
        
        when (config.fitMode) {
            ImageFitMode.FILL -> {
                image.scaleToFit(pageSize.width, pageSize.height)
                image.setFixedPosition(0f, 0f)
            }
            ImageFitMode.FIT_CENTER -> {
                image.scaleToFit(pageSize.width - 2 * config.margin, pageSize.height - 2 * config.margin)
                val x = (pageSize.width - image.imageScaledWidth) / 2
                val y = (pageSize.height - image.imageScaledHeight) / 2
                image.setFixedPosition(x, y)
            }
            ImageFitMode.FIT_WIDTH -> {
                image.scaleToFit(pageSize.width - 2 * config.margin, Float.MAX_VALUE)
                image.setFixedPosition(config.margin, (pageSize.height - image.imageScaledHeight) / 2)
            }
            ImageFitMode.FIT_HEIGHT -> {
                image.scaleToFit(Float.MAX_VALUE, pageSize.height - 2 * config.margin)
                image.setFixedPosition((pageSize.width - image.imageScaledWidth) / 2, config.margin)
            }
            ImageFitMode.ORIGINAL -> {
                val x = (pageSize.width - image.imageWidth) / 2
                val y = (pageSize.height - image.imageHeight) / 2
                image.setFixedPosition(x, y)
            }
        }
        
        Document(destDoc, pageSize).use { doc ->
            doc.add(image)
        }
    }

    override suspend fun insertPdfPages(sourceUri: Uri, insertUri: Uri, position: Int, sourcePages: List<Int>): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val outputFile = createTempOutputFile("pdf_inserted")
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    PdfDocument(PdfReader(context.contentResolver.openInputStream(insertUri))).use { insertDoc ->
                        PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                            val total = srcDoc.numberOfPages
                            
                            for (i in 1..total) {
                                if (i == position) {
                                    val pagesToInsert = if (sourcePages.isEmpty()) {
                                        (1..insertDoc.numberOfPages).toList()
                                    } else {
                                        sourcePages.filter { it in 1..insertDoc.numberOfPages }
                                    }
                                    for (insertPage in pagesToInsert) {
                                        insertDoc.copyPagesTo(insertPage, insertPage, destDoc, PdfPageFormCopier())
                                    }
                                }
                                srcDoc.copyPagesTo(i, i, destDoc, PdfPageFormCopier())
                            }
                            if (position > total) {
                                val pagesToInsert = if (sourcePages.isEmpty()) {
                                    (1..insertDoc.numberOfPages).toList()
                                } else {
                                    sourcePages.filter { it in 1..insertDoc.numberOfPages }
                                }
                                for (insertPage in pagesToInsert) {
                                    insertDoc.copyPagesTo(insertPage, insertPage, destDoc, PdfPageFormCopier())
                                }
                            }
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to insert PDF pages: ${it.message}")) }
        }

    // ==================== SPLIT & MERGE ====================

    override suspend fun splitBySize(sourceUri: Uri, maxSizeMB: Int, outputPrefix: String): AppResult<List<Uri>> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(maxSizeMB > 0) { "Max size must be greater than 0" }
                val maxBytes = maxSizeMB * 1024L * 1024L
                val outputDir = File(context.cacheDir, "pdf_operations/split").apply { mkdirs() }
                val results = mutableListOf<Uri>()
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    var currentDoc: PdfDocument? = null
                    var currentFile: File? = null
                    var partNumber = 1
                    
                    for (i in 1..srcDoc.numberOfPages) {
                        if (currentDoc == null) {
                            currentFile = File(outputDir, "${outputPrefix}_part${partNumber}.pdf")
                            currentDoc = PdfDocument(PdfWriter(currentFile.absolutePath))
                        }
                        
                        srcDoc.copyPagesTo(i, i, currentDoc, PdfPageFormCopier())
                        currentDoc.writer.flush()
                        
                        val currentSize = currentFile?.length() ?: 0L
                        if (currentSize >= maxBytes && i < srcDoc.numberOfPages) {
                            currentDoc.close()
                            results.add(Uri.fromFile(currentFile!!))
                            partNumber++
                            currentDoc = null
                        }
                    }
                    
                    currentDoc?.close()
                    if (currentFile != null && !results.contains(Uri.fromFile(currentFile))) {
                        results.add(Uri.fromFile(currentFile))
                    }
                }
                AppResult.Success(results)
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to split by size: ${it.message}")) }
        }

    override suspend fun splitByBookmark(sourceUri: Uri, outputPrefix: String): AppResult<List<Uri>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val outputDir = File(context.cacheDir, "pdf_operations/split").apply { mkdirs() }
                val results = mutableListOf<Uri>()
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    val outlines = srcDoc.outlines
                        ?: return@withContext AppResult.Error(AppException.PdfProcessingError("No bookmarks found"))
                    
                    val bookmarks = mutableListOf<Pair<String, Int>>()
                    extractBookmarks(outlines, bookmarks)
                    
                    if (bookmarks.isEmpty()) {
                        return@withContext AppResult.Error(AppException.PdfProcessingError("No valid bookmarks found"))
                    }
                    
                    for (i in bookmarks.indices) {
                        val (title, startPage) = bookmarks[i]
                        val endPage = if (i + 1 < bookmarks.size) bookmarks[i + 1].second - 1 else srcDoc.numberOfPages
                        
                        val safeTitle = title.replace(Regex("[^a-zA-Z0-9]"), "_").take(50)
                        val outputFile = File(outputDir, "${outputPrefix}_${safeTitle}.pdf")
                        
                        PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                            srcDoc.copyPagesTo(startPage, endPage.coerceAtMost(srcDoc.numberOfPages), destDoc, PdfPageFormCopier())
                        }
                        results.add(Uri.fromFile(outputFile))
                    }
                }
                AppResult.Success(results)
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to split by bookmark: ${it.message}")) }
        }

    private fun extractBookmarks(outlines: PdfOutline, bookmarks: MutableList<Pair<String, Int>>) {
        var current: PdfOutline? = outlines
        while (current != null) {
            val title = current.title ?: "Untitled"
            val page = try {
                (current.destination as? PdfExplicitDestination)?.pageNumber ?: 1
            } catch (e: Exception) { 1 }
            bookmarks.add(title to page)
            
            current.kids?.forEach { kid ->
                extractBookmarks(kid, bookmarks)
            }
            current = current.next
        }
    }

    override suspend fun splitEveryNPages(sourceUri: Uri, n: Int, outputPrefix: String): AppResult<List<Uri>> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(n > 0) { "N must be greater than 0" }
                val outputDir = File(context.cacheDir, "pdf_operations/split").apply { mkdirs() }
                val results = mutableListOf<Uri>()
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    val total = srcDoc.numberOfPages
                    var partNumber = 1
                    
                    for (start in 1..total step n) {
                        val end = min(start + n - 1, total)
                        val outputFile = File(outputDir, "${outputPrefix}_part${partNumber}.pdf")
                        
                        PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                            srcDoc.copyPagesTo(start, end, destDoc, PdfPageFormCopier())
                        }
                        results.add(Uri.fromFile(outputFile))
                        partNumber++
                    }
                }
                AppResult.Success(results)
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to split every N pages: ${it.message}")) }
        }

    override suspend fun mergePdfs(config: MergeConfig): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(config.sourceUris.isNotEmpty()) { "Source URIs cannot be empty" }
                val outputFile = File(context.cacheDir, "pdf_operations/${config.outputFileName}.pdf").apply {
                    parentFile?.mkdirs()
                }
                
                PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                    for ((index, uri) in config.sourceUris.withIndex()) {
                        PdfDocument(PdfReader(context.contentResolver.openInputStream(uri))).use { srcDoc ->
                            srcDoc.copyPagesTo(1, srcDoc.numberOfPages, destDoc, PdfPageFormCopier())
                        }
                    }
                    
                    if (config.bookmarkMode == BookmarkMergeMode.FLATTEN) {
                        destDoc.outlines?.removeOutlines()
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to merge PDFs: ${it.message}")) }
        }

    override suspend fun combineImagesToPdf(imageUris: List<Uri>, outputName: String, config: ImageInsertionConfig): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(imageUris.isNotEmpty()) { "Image URIs cannot be empty" }
                val outputFile = File(context.cacheDir, "pdf_operations/$outputName.pdf").apply {
                    parentFile?.mkdirs()
                }
                
                PdfDocument(PdfWriter(outputFile.absolutePath)).use { pdfDoc ->
                    for (uri in imageUris) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: continue
                        
                        val pageSize = PageSize(config.pageWidth, config.pageHeight)
                        val page = pdfDoc.addNewPage(pageSize)
                        
                        val imageData = ImageDataFactory.create(bytes)
                        val image = Image(imageData)
                        image.scaleToFit(pageSize.width - 2 * config.margin, pageSize.height - 2 * config.margin)
                        val x = (pageSize.width - image.imageScaledWidth) / 2
                        val y = (pageSize.height - image.imageScaledHeight) / 2
                        image.setFixedPosition(x, y)
                        
                        Document(pdfDoc, pageSize).use { doc ->
                            doc.add(image)
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to combine images: ${it.message}")) }
        }

    // ==================== DOCUMENT ENHANCEMENT ====================

    override suspend fun addPageNumbers(sourceUri: Uri, config: PageNumberConfig): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val outputFile = createTempOutputFile("page_numbers")
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                        srcDoc.copyPagesTo(1, srcDoc.numberOfPages, destDoc, PdfPageFormCopier())
                        
                        val total = destDoc.numberOfPages
                        val start = config.startPage.coerceAtLeast(1)
                        val end = if (config.endPage == -1) total else min(config.endPage, total)
                        val pageSet = if (config.startPage == 1 && config.endPage == -1) null else (start..end).toSet()
                        
                        for (i in start..end) {
                            if (pageSet != null && i !in pageSet) continue
                            
                            val page = destDoc.getPage(i)
                            val pageSize = page.pageSize
                            
                            val numberText = config.format
                                .replace("{n}", (config.startNumber + i - start).toString())
                                .replace("{total}", total.toString())
                            
                            val paragraph = Paragraph(numberText)
                                .setFontSize(config.fontSize)
                                .setFontColor(DeviceRgb(
                                    (config.color shr 16) and 0xFF,
                                    (config.color shr 8) and 0xFF,
                                    config.color and 0xFF
                                ))
                            
                            val (x, y, textAlignment) = when (config.position) {
                                PageNumberPosition.TOP_LEFT -> Triple(36f, pageSize.height - 24f, TextAlignment.LEFT)
                                PageNumberPosition.TOP_CENTER -> Triple(pageSize.width / 2, pageSize.height - 24f, TextAlignment.CENTER)
                                PageNumberPosition.TOP_RIGHT -> Triple(pageSize.width - 36f, pageSize.height - 24f, TextAlignment.RIGHT)
                                PageNumberPosition.BOTTOM_LEFT -> Triple(36f, 24f, TextAlignment.LEFT)
                                PageNumberPosition.BOTTOM_CENTER -> Triple(pageSize.width / 2, 24f, TextAlignment.CENTER)
                                PageNumberPosition.BOTTOM_RIGHT -> Triple(pageSize.width - 36f, 24f, TextAlignment.RIGHT)
                            }
                            
                            Document(destDoc).use { doc ->
                                doc.showTextAligned(paragraph, x, y, i, textAlignment, VerticalAlignment.BOTTOM, 0f)
                            }
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to add page numbers: ${it.message}")) }
        }

    override suspend fun addHeaderFooter(sourceUri: Uri, config: HeaderFooterConfig): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val outputFile = createTempOutputFile("header_footer")
                val color = DeviceRgb(
                    (config.color shr 16) and 0xFF,
                    (config.color shr 8) and 0xFF,
                    config.color and 0xFF
                )
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                        srcDoc.copyPagesTo(1, srcDoc.numberOfPages, destDoc, PdfPageFormCopier())
                        
                        for (i in 1..destDoc.numberOfPages) {
                            val page = destDoc.getPage(i)
                            val pageSize = page.pageSize
                            
                            if (config.headerText.isNotBlank()) {
                                val header = Paragraph(config.headerText)
                                    .setFontSize(config.headerFontSize)
                                    .setFontColor(color)
                                Document(destDoc).use { doc ->
                                    doc.showTextAligned(header, pageSize.width / 2, pageSize.height - config.marginTop / 2,
                                        i, TextAlignment.CENTER, VerticalAlignment.TOP, 0f)
                                }
                            }
                            
                            if (config.footerText.isNotBlank()) {
                                val footer = Paragraph(config.footerText)
                                    .setFontSize(config.footerFontSize)
                                    .setFontColor(color)
                                Document(destDoc).use { doc ->
                                    doc.showTextAligned(footer, pageSize.width / 2, config.marginBottom / 2,
                                        i, TextAlignment.CENTER, VerticalAlignment.BOTTOM, 0f)
                                }
                            }
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to add header/footer: ${it.message}")) }
        }

    override suspend fun addWatermark(sourceUri: Uri, config: WatermarkConfig): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val outputFile = createTempOutputFile("watermarked")
                val pageSet = if (config.pages.isEmpty()) null else config.pages.toSet()
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                        srcDoc.copyPagesTo(1, srcDoc.numberOfPages, destDoc, PdfPageFormCopier())
                        
                        for (i in 1..destDoc.numberOfPages) {
                            if (pageSet != null && i !in pageSet) continue
                            
                            val page = destDoc.getPage(i)
                            val pageSize = page.pageSize
                            val canvas = PdfCanvas(page)
                            
                            val gs = PdfExtGState().apply { fillOpacity = config.opacity }
                            canvas.saveState()
                            canvas.setExtGState(gs)
                            
                            if (config.imageUri != null) {
                                val imageBytes = context.contentResolver.openInputStream(config.imageUri)?.use { it.readBytes() }
                                if (imageBytes != null) {
                                    val imageData = ImageDataFactory.create(imageBytes)
                                    val image = Image(imageData)
                                    image.scaleToFit(pageSize.width * 0.4f, pageSize.height * 0.4f)
                                    val x = (pageSize.width - image.imageScaledWidth) / 2
                                    val y = (pageSize.height - image.imageScaledHeight) / 2
                                    image.setFixedPosition(x, y)
                                    Document(destDoc).use { doc -> doc.add(image) }
                                }
                            } else if (config.text.isNotBlank()) {
                                val paragraph = Paragraph(config.text)
                                    .setFontSize(config.fontSize)
                                    .setFontColor(DeviceRgb(
                                        (config.color shr 16) and 0xFF,
                                        (config.color shr 8) and 0xFF,
                                        config.color and 0xFF
                                    ))
                                
                                Document(destDoc).use { doc ->
                                    doc.showTextAligned(paragraph, pageSize.width / 2, pageSize.height / 2, i,
                                        TextAlignment.CENTER, VerticalAlignment.MIDDLE,
                                        Math.toRadians(config.rotation.toDouble()).toFloat())
                                }
                            }
                            
                            canvas.restoreState()
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to add watermark: ${it.message}")) }
        }

    override suspend fun addBackground(sourceUri: Uri, config: BackgroundConfig): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val outputFile = createTempOutputFile("background")
                val pageSet = if (config.pages.isEmpty()) null else config.pages.toSet()
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                        srcDoc.copyPagesTo(1, srcDoc.numberOfPages, destDoc, PdfPageFormCopier())
                        
                        for (i in 1..destDoc.numberOfPages) {
                            if (pageSet != null && i !in pageSet) continue
                            
                            val page = destDoc.getPage(i)
                            val canvas = PdfCanvas(page)
                            val pageSize = page.pageSize
                            
                            canvas.saveState()
                            val gs = PdfExtGState().apply { fillOpacity = config.opacity }
                            canvas.setExtGState(gs)
                            
                            if (config.imageUri != null) {
                                val imageBytes = context.contentResolver.openInputStream(config.imageUri)?.use { it.readBytes() }
                                if (imageBytes != null) {
                                    val imageData = ImageDataFactory.create(imageBytes)
                                    canvas.addImageFittedIntoRectangle(imageData, pageSize, false)
                                }
                            } else {
                                canvas.setFillColor(DeviceRgb(
                                    (config.color shr 16) and 0xFF,
                                    (config.color shr 8) and 0xFF,
                                    config.color and 0xFF
                                ))
                                canvas.rectangle(pageSize)
                                canvas.fill()
                            }
                            
                            canvas.restoreState()
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to add background: ${it.message}")) }
        }

    // ==================== COMPRESSION & OPTIMIZATION ====================

    override suspend fun compressPdf(sourceUri: Uri, config: CompressConfig): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val outputFile = createTempOutputFile("compressed")
                
                val writerProperties = PdfWriterProperties().apply {
                    setFullCompressionMode(true)
                }
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    PdfDocument(PdfWriter(outputFile.absolutePath, writerProperties)).use { destDoc ->
                        for (i in 1..srcDoc.numberOfPages) {
                            val page = srcDoc.getPage(i)
                            val newPage = page.copyTo(destDoc)
                            destDoc.addPage(newPage)
                        }
                        
                        if (config.removeMetadata) {
                            destDoc.documentInfo.clear()
                        }
                        if (config.removeUnusedObjects) {
                            destDoc.removeUnusedObjects()
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to compress PDF: ${it.message}")) }
        }

    override suspend fun optimizePdf(sourceUri: Uri, aggressive: Boolean): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val outputFile = createTempOutputFile("optimized")
                
                PdfDocument(PdfReader(context.contentResolver.openInputStream(sourceUri))).use { srcDoc ->
                    PdfDocument(PdfWriter(outputFile.absolutePath)).use { destDoc ->
                        srcDoc.copyPagesTo(1, srcDoc.numberOfPages, destDoc, PdfPageFormCopier())
                        destDoc.removeUnusedObjects()
                        
                        if (aggressive) {
                            val acroForm = PdfAcroForm.getAcroForm(destDoc, false)
                            acroForm?.flattenFields()
                        }
                    }
                }
                AppResult.Success(Uri.fromFile(outputFile))
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to optimize PDF: ${it.message}")) }
        }

    // ==================== UTILITY ====================

    override suspend fun getPageCount(uri: Uri): AppResult<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                PdfDocument(PdfReader(context.contentResolver.openInputStream(uri))).use { doc ->
                    AppResult.Success(doc.numberOfPages)
                }
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to get page count: ${it.message}")) }
        }

    override suspend fun getPageThumbnails(uri: Uri, pageNumbers: List<Int>): AppResult<List<Bitmap>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bitmaps = mutableListOf<Bitmap>()
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withContext AppResult.Error(AppException.FileNotFound("Cannot open PDF"))
                
                PdfRenderer(pfd).use { renderer ->
                    for (pageNum in pageNumbers) {
                        val zeroBased = pageNum - 1
                        if (zeroBased in 0 until renderer.pageCount) {
                            renderer.openPage(zeroBased).use { page ->
                                val width = 200
                                val height = (width.toFloat() / page.width * page.height).toInt()
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bitmaps.add(bitmap)
                            }
                        }
                    }
                }
                AppResult.Success(bitmaps)
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to get thumbnails: ${it.message}")) }
        }

    override suspend fun getPageInfo(uri: Uri, pageNumber: Int): AppResult<PdfPage> =
        withContext(Dispatchers.IO) {
            runCatching {
                PdfDocument(PdfReader(context.contentResolver.openInputStream(uri))).use { doc ->
                    if (pageNumber !in 1..doc.numberOfPages) {
                        return@withContext AppResult.Error(AppException.InvalidPage("Page $pageNumber out of range"))
                    }
                    val page = doc.getPage(pageNumber)
                    val size = page.pageSize
                    AppResult.Success(PdfPage(
                        pageNumber = pageNumber,
                        width = size.width,
                        height = size.height,
                        rotation = page.rotation
                    ))
                }
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to get page info: ${it.message}")) }
        }

    override suspend fun renderPageToBitmap(uri: Uri, pageNumber: Int, width: Int): AppResult<Bitmap> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withContext AppResult.Error(AppException.FileNotFound("Cannot open PDF"))
                
                PdfRenderer(pfd).use { renderer ->
                    val zeroBased = pageNumber - 1
                    if (zeroBased !in 0 until renderer.pageCount) {
                        return@withContext AppResult.Error(AppException.InvalidPage("Page $pageNumber out of range"))
                    }
                    
                    renderer.openPage(zeroBased).use { page ->
                        val height = (width.toFloat() / page.width * page.height).toInt()
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        AppResult.Success(bitmap)
                    }
                }
            }.getOrElse { AppResult.Error(AppException.PdfProcessingError("Failed to render page: ${it.message}")) }
        }

    private fun getFileSize(uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
