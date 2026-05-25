// viewer/src/main/java/com/propdf/viewer/pdf/PdfToolEngine.kt
package com.propdf.viewer.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import com.propdf.core.saf.SafEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfToolEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val safEngine: SafEngine
) {

    init { PDFBoxResourceLoader.init(context) }

    // ==================== MERGE ====================

    suspend fun mergePdfs(
        sources: List<Uri>,
        outputUri: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): AppResult<Uri> = withContext(Dispatchers.IO) {
        try {
            val tempFiles = mutableListOf<File>()
            val mergedDoc = PDDocument()
            var totalPages = 0
            var processedPages = 0

            // Phase 1: Resolve all URIs to temp files
            sources.forEach { uri ->
                when (val result = safEngine.resolveToFile(uri)) {
                    is AppResult.Success -> tempFiles.add(result.data)
                    is AppResult.Error -> {
                        mergedDoc.close()
                        tempFiles.forEach { it.delete() }
                        return@withContext AppResult.Error(result.exception)
                    }
                    else -> {}
                }
            }

            // Count total pages
            tempFiles.forEach { file ->
                PDDocument.load(file).use { doc -> totalPages += doc.numberOfPages }
            }

            // Phase 2: Merge
            tempFiles.forEach { file ->
                if (!isActive) {
                    mergedDoc.close()
                    tempFiles.forEach { it.delete() }
                    return@withContext AppResult.Error(AppException.IOError("Merge cancelled"))
                }
                PDDocument.load(file).use { doc ->
                    for (i in 0 until doc.numberOfPages) {
                        if (!isActive) {
                            mergedDoc.close()
                            tempFiles.forEach { it.delete() }
                            return@withContext AppResult.Error(AppException.IOError("Merge cancelled"))
                        }
                        mergedDoc.addPage(doc.getPage(i))
                        processedPages++
                        onProgress(processedPages, totalPages)
                        yield()
                    }
                }
            }

            // Phase 3: Save to output URI
            when (val outResult = safEngine.openOutputStream(outputUri)) {
                is AppResult.Success -> {
                    outResult.data.use { stream ->
                        mergedDoc.save(stream)
                    }
                    mergedDoc.close()
                    tempFiles.forEach { it.delete() }
                    AppResult.Success(outputUri)
                }
                is AppResult.Error -> {
                    mergedDoc.close()
                    tempFiles.forEach { it.delete() }
                    AppResult.Error(outResult.exception)
                }
                else -> {
                    mergedDoc.close()
                    tempFiles.forEach { it.delete() }
                    AppResult.Error(AppException.Unknown("Unexpected result"))
                }
            }
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Merge failed: ${e.message}"))
        }
    }

    // ==================== SPLIT ====================

    suspend fun splitPdf(
        sourceUri: Uri,
        outputDir: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): AppResult<List<Uri>> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = when (val result = safEngine.resolveToFile(sourceUri)) {
                is AppResult.Success -> result.data
                is AppResult.Error -> return@withContext AppResult.Error(result.exception)
                else -> return@withContext AppResult.Error(AppException.Unknown("Unexpected"))
            }

            PDDocument.load(sourceFile).use { doc ->
                val pageCount = doc.numberOfPages
                val outputUris = mutableListOf<Uri>()

                for (i in 0 until pageCount) {
                    if (!isActive) return@withContext AppResult.Error(AppException.IOError("Split cancelled"))

                    val newDoc = PDDocument()
                    newDoc.addPage(doc.getPage(i))

                    // Create output URI for this page
                    val outFile = File(context.cacheDir, "split/page_${i + 1}.pdf")
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { stream -> newDoc.save(stream) }
                    newDoc.close()

                    outputUris.add(Uri.fromFile(outFile))
                    onProgress(i + 1, pageCount)
                    yield()
                }

                AppResult.Success(outputUris.toList())
            }
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Split failed: ${e.message}"))
        }
    }

    // ==================== ROTATE ====================

    suspend fun rotatePages(
        sourceUri: Uri,
        pageIndices: List<Int>,
        degrees: Int,
        outputUri: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): AppResult<Uri> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = when (val result = safEngine.resolveToFile(sourceUri)) {
                is AppResult.Success -> result.data
                is AppResult.Error -> return@withContext AppResult.Error(result.exception)
                else -> return@withContext AppResult.Error(AppException.Unknown("Unexpected"))
            }

            PDDocument.load(sourceFile).use { doc ->
                val normalizedDegrees = ((degrees % 360) + 360) % 360
                pageIndices.forEach { pageIndex ->
                    if (!isActive) return@withContext AppResult.Error(AppException.IOError("Rotate cancelled"))
                    if (pageIndex in 0 until doc.numberOfPages) {
                        val page = doc.getPage(pageIndex)
                        page.rotation = (page.rotation + normalizedDegrees) % 360
                    }
                    onProgress(pageIndex + 1, pageIndices.size)
                    yield()
                }

                when (val outResult = safEngine.openOutputStream(outputUri)) {
                    is AppResult.Success -> {
                        outResult.data.use { stream -> doc.save(stream) }
                        AppResult.Success(outputUri)
                    }
                    is AppResult.Error -> AppResult.Error(outResult.exception)
                    else -> AppResult.Error(AppException.Unknown("Unexpected"))
                }
            }
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Rotate failed: ${e.message}"))
        }
    }

    // ==================== COMPRESS ====================

    suspend fun compressPdf(
        sourceUri: Uri,
        outputUri: Uri,
        options: CompressionOptions = CompressionOptions(),
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): AppResult<Uri> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = when (val result = safEngine.resolveToFile(sourceUri)) {
                is AppResult.Success -> result.data
                is AppResult.Error -> return@withContext AppResult.Error(result.exception)
                else -> return@withContext AppResult.Error(AppException.Unknown("Unexpected"))
            }

            PDDocument.load(sourceFile).use { doc ->
                val totalPages = doc.numberOfPages
                for (i in 0 until totalPages) {
                    if (!isActive) return@withContext AppResult.Error(AppException.IOError("Compress cancelled"))
                    val page = doc.getPage(i)
                    compressPage(page, doc, options)
                    onProgress(i + 1, totalPages)
                    yield()
                }
                if (options.optimizeFonts) removeAcroForm(doc)

                when (val outResult = safEngine.openOutputStream(outputUri)) {
                    is AppResult.Success -> {
                        outResult.data.use { stream -> doc.save(stream) }
                        AppResult.Success(outputUri)
                    }
                    is AppResult.Error -> AppResult.Error(outResult.exception)
                    else -> AppResult.Error(AppException.Unknown("Unexpected"))
                }
            }
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Compress failed: ${e.message}"))
        }
    }

    // ==================== PRIVATE HELPERS ====================

    private fun compressPage(page: com.tom_roush.pdfbox.pdmodel.PDPage, document: PDDocument, options: CompressionOptions) {
        val resources = page.resources ?: return
        val imageNames = resources.xObjectNames?.toList() ?: return
        imageNames.forEach { name ->
            val xObject = resources.getXObject(name) as? PDImageXObject ?: return@forEach
            val quality = when (options.quality) {
                CompressionQuality.LOW -> 0.3f
                CompressionQuality.MEDIUM -> 0.5f
                CompressionQuality.HIGH -> 0.7f
            }
            val compressedImage = recompressImage(xObject, document, options, quality)
            compressedImage?.let { resources.put(name, it) }
        }
    }

    private fun recompressImage(image: PDImageXObject, document: PDDocument, options: CompressionOptions, quality: Float): PDImageXObject? {
        return try {
            val bitmap = image.image ?: return null
            val width = bitmap.width
            val height = bitmap.height
            if (width * height < 10000) return null
            val processedBitmap = if (options.grayscale) convertToGrayscale(bitmap) else bitmap
            val result = if (quality < 0.6f) {
                JPEGFactory.createFromImage(document, processedBitmap, quality)
            } else {
                LosslessFactory.createFromImage(document, processedBitmap)
            }
            if (processedBitmap != bitmap) processedBitmap.recycle()
            result
        } catch (e: Exception) { null }
    }

    private fun convertToGrayscale(bitmap: Bitmap): Bitmap {
        val grayscale = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(grayscale)
        val paint = Paint()
        val matrix = android.graphics.ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return grayscale
    }

    private fun removeAcroForm(doc: PDDocument) {
        try {
            val catalog = doc.document?.catalog
            if (catalog != null) {
                val acroFormField = catalog.javaClass.getDeclaredField("acroForm")
                acroFormField.isAccessible = true
                acroFormField.set(catalog, null)
            }
        } catch (e: Exception) { /* Field may not exist, ignore */ }
    }
}

data class CompressionOptions(
    val quality: CompressionQuality = CompressionQuality.MEDIUM,
    val grayscale: Boolean = false,
    val optimizeFonts: Boolean = true,
    val removeMetadata: Boolean = false
)

enum class CompressionQuality { LOW, MEDIUM, HIGH }
