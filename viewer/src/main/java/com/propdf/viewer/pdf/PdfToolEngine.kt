package com.propdf.viewer.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class PdfToolEngine(private val context: Context) {

    init {
        PDFBoxResourceLoader.init(context)
    }

    suspend fun mergePdfs(
        sources: List<Uri>,
        outputFile: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val mergedDoc = PDDocument()
            var totalPages = 0
            var processedPages = 0

            sources.forEach { uri ->
                val doc = loadDocument(uri)
                totalPages += doc?.numberOfPages ?: 0
                doc?.close()
            }

            sources.forEach { uri ->
                if (!isActive) {
                    mergedDoc.close()
                    return@withContext Result.failure(CancellationException("Merge cancelled"))
                }
                val doc = loadDocument(uri) ?: return@forEach
                val pages = doc.pages
                for (i in 0 until pages.count) {
                    if (!isActive) {
                        mergedDoc.close()
                        doc.close()
                        return@withContext Result.failure(CancellationException("Merge cancelled"))
                    }
                    val page = pages.get(i)
                    mergedDoc.addPage(page)
                    processedPages++
                    onProgress(processedPages, totalPages)
                    yield()
                }
                doc.close()
            }

            mergedDoc.save(FileOutputStream(outputFile))
            mergedDoc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun splitPdf(
        sourceUri: Uri,
        outputDir: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        try {
            val doc = loadDocument(sourceUri) ?: return@withContext Result.failure(
                IOException("Cannot load PDF")
            )
            val pageCount = doc.numberOfPages
            val outputFiles = mutableListOf<File>()

            for (i in 0 until pageCount) {
                if (!isActive) {
                    doc.close()
                    return@withContext Result.failure(CancellationException("Split cancelled"))
                }
                val newDoc = PDDocument()
                newDoc.addPage(doc.getPage(i))
                val outFile = File(outputDir, "page_${i + 1}.pdf")
                newDoc.save(FileOutputStream(outFile))
                newDoc.close()
                outputFiles.add(outFile)
                onProgress(i + 1, pageCount)
                yield()
            }
            doc.close()
            Result.success(outputFiles)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun extractPages(
        sourceUri: Uri,
        pageIndices: List<Int>,
        outputFile: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val doc = loadDocument(sourceUri) ?: return@withContext Result.failure(
                IOException("Cannot load PDF")
            )
            val newDoc = PDDocument()
            pageIndices.forEachIndexed { index, pageIndex ->
                if (!isActive) {
                    doc.close()
                    newDoc.close()
                    return@withContext Result.failure(CancellationException("Extract cancelled"))
                }
                if (pageIndex in 0 until doc.numberOfPages) {
                    newDoc.addPage(doc.getPage(pageIndex))
                }
                onProgress(index + 1, pageIndices.size)
                yield()
            }
            newDoc.save(FileOutputStream(outputFile))
            newDoc.close()
            doc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reorderPages(
        sourceUri: Uri,
        newOrder: List<Int>,
        outputFile: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val doc = loadDocument(sourceUri) ?: return@withContext Result.failure(
                IOException("Cannot load PDF")
            )
            val newDoc = PDDocument()
            newOrder.forEachIndexed { index, pageIndex ->
                if (!isActive) {
                    doc.close()
                    newDoc.close()
                    return@withContext Result.failure(CancellationException("Reorder cancelled"))
                }
                if (pageIndex in 0 until doc.numberOfPages) {
                    newDoc.addPage(doc.getPage(pageIndex))
                }
                onProgress(index + 1, newOrder.size)
                yield()
            }
            newDoc.save(FileOutputStream(outputFile))
            newDoc.close()
            doc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun duplicatePage(
        sourceUri: Uri,
        pageIndex: Int,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val doc = loadDocument(sourceUri) ?: return@withContext Result.failure(
                IOException("Cannot load PDF")
            )
            val newDoc = PDDocument()
            for (i in 0 until doc.numberOfPages) {
                newDoc.addPage(doc.getPage(i))
                if (i == pageIndex) {
                    newDoc.addPage(doc.getPage(i))
                }
                yield()
            }
            newDoc.save(FileOutputStream(outputFile))
            newDoc.close()
            doc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rotatePages(
        sourceUri: Uri,
        pageIndices: List<Int>,
        degrees: Int,
        outputFile: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val doc = loadDocument(sourceUri) ?: return@withContext Result.failure(
                IOException("Cannot load PDF")
            )
            val normalizedDegrees = ((degrees % 360) + 360) % 360
            pageIndices.forEach { pageIndex ->
                if (!isActive) {
                    doc.close()
                    return@withContext Result.failure(CancellationException("Rotate cancelled"))
                }
                if (pageIndex in 0 until doc.numberOfPages) {
                    val page = doc.getPage(pageIndex)
                    page.rotation = (page.rotation + normalizedDegrees) % 360
                }
                yield()
            }
            doc.save(FileOutputStream(outputFile))
            doc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePages(
        sourceUri: Uri,
        pageIndices: List<Int>,
        outputFile: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val doc = loadDocument(sourceUri) ?: return@withContext Result.failure(
                IOException("Cannot load PDF")
            )
            val newDoc = PDDocument()
            val indicesToDelete = pageIndices.toSet()
            val total = doc.numberOfPages
            for (i in 0 until total) {
                if (!isActive) {
                    doc.close()
                    newDoc.close()
                    return@withContext Result.failure(CancellationException("Delete cancelled"))
                }
                if (i !in indicesToDelete) {
                    newDoc.addPage(doc.getPage(i))
                }
                onProgress(i + 1, total)
                yield()
            }
            newDoc.save(FileOutputStream(outputFile))
            newDoc.close()
            doc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun compressPdf(
        sourceUri: Uri,
        outputFile: File,
        options: CompressionOptions,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val doc = loadDocument(sourceUri) ?: return@withContext Result.failure(
                IOException("Cannot load PDF")
            )
            val totalPages = doc.numberOfPages
            for (i in 0 until totalPages) {
                if (!isActive) {
                    doc.close()
                    return@withContext Result.failure(CancellationException("Compress cancelled"))
                }
                val page = doc.getPage(i)
                compressPage(page, doc, options)
                onProgress(i + 1, totalPages)
                yield()
            }
            if (options.optimizeFonts) {
                removeAcroForm(doc)
            }
            doc.save(FileOutputStream(outputFile))
            doc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun removeAcroForm(doc: PDDocument) {
        try {
            val catalog = doc.document?.catalog
            if (catalog != null) {
                val acroFormField = catalog.javaClass.getDeclaredField("acroForm")
                acroFormField.isAccessible = true
                acroFormField.set(catalog, null)
            }
        } catch (e: Exception) {
            // Field may not exist in this PDFBox version, ignore silently
        }
    }

    private fun compressPage(
        page: com.tom_roush.pdfbox.pdmodel.PDPage,
        document: PDDocument,
        options: CompressionOptions
    ) {
        val resources = page.resources ?: return
        val imageNames = resources.xObjectNames?.toList() ?: return
        imageNames.forEach { name ->
            val xObject = resources.getXObject(name) as? PDImageXObject ?: return@forEach
            val compressedImage = when (options.quality) {
                CompressionQuality.LOW -> recompressImage(xObject, document, options, 0.3f)
                CompressionQuality.MEDIUM -> recompressImage(xObject, document, options, 0.5f)
                CompressionQuality.HIGH -> recompressImage(xObject, document, options, 0.7f)
            }
            compressedImage?.let { resources.put(name, it) }
        }
    }

    private fun recompressImage(
        image: PDImageXObject,
        document: PDDocument,
        options: CompressionOptions,
        quality: Float
    ): PDImageXObject? {
        return try {
            val bitmap = image.image ?: return null
            val width = bitmap.width
            val height = bitmap.height
            if (width * height < 10000) return null
            val processedBitmap = if (options.grayscale) {
                convertToGrayscale(bitmap)
            } else bitmap
            val result = if (quality < 0.6f) {
                JPEGFactory.createFromImage(document, processedBitmap, quality)
            } else {
                LosslessFactory.createFromImage(document, processedBitmap)
            }
            if (processedBitmap != bitmap) processedBitmap.recycle()
            result
        } catch (e: Exception) {
            null
        }
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

    private fun loadDocument(uri: Uri): PDDocument? {
        return try {
            val file = File(uri.path ?: return null)
            if (!file.exists()) return null
            PDDocument.load(file)
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileSize(uri: Uri): Long {
        return try {
            File(uri.path ?: return 0).length()
        } catch (e: Exception) {
            0
        }
    }

    class CancellationException(message: String) : Exception(message)
}

data class CompressionOptions(
    val quality: CompressionQuality = CompressionQuality.MEDIUM,
    val grayscale: Boolean = false,
    val optimizeFonts: Boolean = true,
    val removeMetadata: Boolean = false
)

enum class CompressionQuality {
    LOW, MEDIUM, HIGH
}
