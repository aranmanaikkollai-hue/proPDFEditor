package com.propdf.editor.data.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.media.ExifInterface
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image
import com.itextpdf.io.image.ImageDataFactory
import com.propdf.editor.domain.model.ConversionResult
import com.propdf.editor.utils.FileUtils
import com.propdf.editor.utils.MemoryUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.min

@Singleton
class ImageConverter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val MAX_BITMAP_DIMENSION = 4096
        private const val COMPRESSION_QUALITY = 85
        private const val PDF_PAGE_WIDTH = 595f
        private const val PDF_PAGE_HEIGHT = 842f
    }
    
    suspend fun toPdf(
        context: Context,
        sourceUris: List<Uri>,
        outputDir: File,
        fileName: String,
        onProgress: suspend (Int) -> Unit
    ): ConversionResult = withContext(Dispatchers.IO) {
        try {
            if (sourceUris.isEmpty()) {
                return@withContext ConversionResult(false, null, fileName, "No images selected")
            }
            
            val outputFile = File(outputDir, "$fileName.pdf")
            val writer = PdfWriter(outputFile.absolutePath)
            val pdfDoc = PdfDocument(writer)
            val document = Document(pdfDoc, PageSize.A4)
            
            val totalImages = sourceUris.size
            
            sourceUris.forEachIndexed { index, uri ->
                if (!coroutineContext.isActive) {
                    document.close()
                    pdfDoc.close()
                    outputFile.delete()
                    return@withContext ConversionResult(false, null, fileName, "Cancelled")
                }
                
                try {
                    val bitmap = loadBitmapWithExif(context, uri)
                        ?: return@forEachIndexed
                    
                    val scaledBitmap = scaleBitmapForPdf(bitmap)
                    if (scaledBitmap != bitmap) {
                        bitmap.recycle()
                    }
                    
                    val byteArray = bitmapToByteArray(scaledBitmap)
                    val imageData = ImageDataFactory.create(byteArray)
                    val image = Image(imageData)
                    
                    // Scale to fit page with margins
                    val pageWidth = PDF_PAGE_WIDTH - 72f
                    val pageHeight = PDF_PAGE_HEIGHT - 72f
                    val imageWidth = image.imageScaledWidth
                    val imageHeight = image.imageScaledHeight
                    
                    val scale = min(
                        pageWidth / imageWidth,
                        pageHeight / imageHeight
                    )
                    
                    image.scale(scale, scale)
                    image.setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.CENTER)
                    
                    document.add(image)
                    
                    if (index < sourceUris.size - 1) {
                        document.add(com.itextpdf.layout.element.AreaBreak(com.itextpdf.layout.properties.AreaBreakType.NEXT_PAGE))
                    }
                    
                    if (scaledBitmap != bitmap) {
                        scaledBitmap.recycle()
                    }
                    
                    onProgress(((index + 1) * 100) / totalImages)
                    
                } catch (e: Exception) {
                    // Skip problematic images, continue with others
                }
            }
            
            document.close()
            pdfDoc.close()
            
            val outputUri = FileUtils.getUriForFile(context, outputFile)
            ConversionResult(
                true,
                outputUri,
                fileName,
                "Created PDF from $totalImages images",
                totalBytes = outputFile.length()
            )
            
        } catch (e: OutOfMemoryError) {
            System.gc()
            ConversionResult(false, null, fileName, "Out of memory. Try with fewer images.")
        } catch (e: Exception) {
            ConversionResult(false, null, fileName, e.message ?: "Images to PDF failed")
        }
    }
    
    suspend fun mergeImages(
        context: Context,
        sourceUris: List<Uri>,
        outputDir: File,
        fileName: String,
        onProgress: suspend (Int) -> Unit
    ): ConversionResult = withContext(Dispatchers.IO) {
        try {
            if (sourceUris.size < 2) {
                return@withContext ConversionResult(false, null, fileName, "Need at least 2 images to merge")
            }
            
            val bitmaps = mutableListOf<Bitmap>()
            var totalWidth = 0
            var maxHeight = 0
            
            // Load all bitmaps
            sourceUris.forEach { uri ->
                val bitmap = loadBitmapWithExif(context, uri) ?: return@forEach
                val scaled = scaleBitmapForMerge(bitmap)
                if (scaled != bitmap) bitmap.recycle()
                
                bitmaps.add(scaled)
                totalWidth += scaled.width
                maxHeight = maxOf(maxHeight, scaled.height)
            }
            
            if (bitmaps.isEmpty()) {
                return@withContext ConversionResult(false, null, fileName, "Could not load any images")
            }
            
            // Check memory
            val estimatedMemory = totalWidth * maxHeight * 4L
            if (!MemoryUtils.hasAvailableMemory(estimatedMemory * 2)) {
                bitmaps.forEach { it.recycle() }
                return@withContext ConversionResult(false, null, fileName, "Not enough memory for merge")
            }
            
            val result = Bitmap.createBitmap(totalWidth, maxHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            
            var currentX = 0
            bitmaps.forEachIndexed { index, bitmap ->
                if (!coroutineContext.isActive) {
                    result.recycle()
                    bitmaps.forEach { it.recycle() }
                    return@withContext ConversionResult(false, null, fileName, "Cancelled")
                }
                
                val top = (maxHeight - bitmap.height) / 2
                canvas.drawBitmap(bitmap, currentX.toFloat(), top.toFloat(), paint)
                currentX += bitmap.width
                bitmap.recycle()
                
                onProgress(((index + 1) * 100) / bitmaps.size)
            }
            
            val outputFile = File(outputDir, "${fileName}_merged.png")
            FileOutputStream(outputFile).use { out ->
                result.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            result.recycle()
            
            val outputUri = FileUtils.getUriForFile(context, outputFile)
            ConversionResult(
                true,
                outputUri,
                fileName,
                "Merged ${bitmaps.size} images horizontally",
                totalBytes = outputFile.length()
            )
            
        } catch (e: OutOfMemoryError) {
            System.gc()
            ConversionResult(false, null, fileName, "Out of memory")
        } catch (e: Exception) {
            ConversionResult(false, null, fileName, e.message ?: "Merge failed")
        }
    }
    
    suspend fun splitImage(
        context: Context,
        sourceUri: Uri,
        outputDir: File,
        fileName: String,
        onProgress: suspend (Int) -> Unit
    ): ConversionResult = withContext(Dispatchers.IO) {
        try {
            val bitmap = loadBitmapWithExif(context, sourceUri)
                ?: return@withContext ConversionResult(false, null, fileName, "Cannot load image")
            
            val width = bitmap.width
            val height = bitmap.height
            
            // Split into 2 equal parts vertically
            val splitWidth = width / 2
            
            val part1 = Bitmap.createBitmap(bitmap, 0, 0, splitWidth, height)
            val part2 = Bitmap.createBitmap(bitmap, splitWidth, 0, width - splitWidth, height)
            
            bitmap.recycle()
            
            val file1 = File(outputDir, "${fileName}_part1.png")
            val file2 = File(outputDir, "${fileName}_part2.png")
            
            FileOutputStream(file1).use { out ->
                part1.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            FileOutputStream(file2).use { out ->
                part2.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            part1.recycle()
            part2.recycle()
            
            onProgress(100)
            
            val outputUri = FileUtils.getUriForFile(context, file1)
            ConversionResult(
                true,
                outputUri,
                fileName,
                "Split image into 2 parts",
                fileCount = 2,
                totalBytes = file1.length() + file2.length()
            )
            
        } catch (e: Exception) {
            ConversionResult(false, null, fileName, e.message ?: "Split failed")
        }
    }
    
    private fun loadBitmapWithExif(context: Context, uri: Uri): Bitmap? {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        
        return inputStream.use { stream ->
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(stream, null, options)
            
            // Calculate sample size to avoid OOM
            var sampleSize = 1
            while (options.outWidth / sampleSize > MAX_BITMAP_DIMENSION || 
                   options.outHeight / sampleSize > MAX_BITMAP_DIMENSION) {
                sampleSize *= 2
            }
            
            val finalOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            
            val newStream = context.contentResolver.openInputStream(uri) ?: return null
            newStream.use { BitmapFactory.decodeStream(it, null, finalOptions) }
        }?.let { bitmap ->
            // Handle EXIF rotation
            val rotation = getExifRotation(context, uri)
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) bitmap.recycle()
                rotated
            } else {
                bitmap
            }
        }
    }
    
    private fun getExifRotation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }
    
    private fun scaleBitmapForPdf(bitmap: Bitmap): Bitmap {
        val maxDim = 2048
        if (bitmap.width <= maxDim && bitmap.height <= maxDim) return bitmap
        
        val scale = min(
            maxDim.toFloat() / bitmap.width,
            maxDim.toFloat() / bitmap.height
        )
        
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    private fun scaleBitmapForMerge(bitmap: Bitmap): Bitmap {
        val maxHeight = 2048
        if (bitmap.height <= maxHeight) return bitmap
        
        val scale = maxHeight.toFloat() / bitmap.height
        val newWidth = (bitmap.width * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, maxHeight, true)
    }
    
    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, stream)
        return stream.toByteArray()
    }
}
