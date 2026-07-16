package com.propdf.editor.data.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import com.itextpdf.kernel.pdf.canvas.parser.listener.LocationTextExtractionStrategy
import com.propdf.editor.domain.model.ConversionResult
import com.propdf.editor.utils.FileUtils
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class PdfConverter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun toImages(
        context: Context,
        sourceUri: Uri,
        outputDir: File,
        baseFileName: String,
        onProgress: suspend (Int) -> Unit
    ): ConversionResult = withContext(Dispatchers.IO) {
        try {
            val pfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                ?: return@withContext ConversionResult(false, null, baseFileName, "Cannot open PDF")
            
            pfd.use { descriptor ->
                val renderer = PdfRenderer(descriptor)
                val totalPages = renderer.pageCount
                val outputFiles = mutableListOf<File>()
                
                for (i in 0 until totalPages) {
                    if (!coroutineContext.isActive) {
                        renderer.close()
                        cleanupFiles(outputFiles)
                        return@withContext ConversionResult(false, null, baseFileName, "Cancelled")
                    }
                    
                    renderer.openPage(i).use { page ->
                        val bitmap = Bitmap.createBitmap(
                            page.width * 2,
                            page.height * 2,
                            Bitmap.Config.ARGB_8888
                        )
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        
                        val outputFile = File(outputDir, "${baseFileName}_page_${i + 1}.png")
                        FileOutputStream(outputFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        bitmap.recycle()
                        outputFiles.add(outputFile)
                    }
                    
                    onProgress(((i + 1) * 100) / totalPages)
                }
                
                renderer.close()
                
                if (outputFiles.isEmpty()) {
                    return@withContext ConversionResult(false, null, baseFileName, "No pages rendered")
                }
                
                val firstFileUri = FileUtils.getUriForFile(context, outputFiles.first())
                ConversionResult(
                    true,
                    firstFileUri,
                    baseFileName,
                    "Converted $totalPages pages to images",
                    fileCount = outputFiles.size,
                    totalBytes = outputFiles.sumOf { it.length() }
                )
            }
        } catch (e: Exception) {
            ConversionResult(false, null, baseFileName, e.message ?: "PDF to images failed")
        }
    }
    
    suspend fun toText(
        context: Context,
        sourceUri: Uri,
        outputDir: File,
        fileName: String,
        onProgress: suspend (Int) -> Unit
    ): ConversionResult = withContext(Dispatchers.IO) {
        try {
            val tempFile = FileUtils.copyUriToTempFile(context, sourceUri)
                ?: return@withContext ConversionResult(false, null, fileName, "Cannot access PDF")
            
            try {
                val reader = PdfReader(tempFile.absolutePath)
                val pdfDoc = PdfDocument(reader)
                val totalPages = pdfDoc.numberOfPages
                val stringBuilder = StringBuilder()
                
                for (i in 1..totalPages) {
                    if (!coroutineContext.isActive) {
                        pdfDoc.close()
                        return@withContext ConversionResult(false, null, fileName, "Cancelled")
                    }
                    
                    val page = pdfDoc.getPage(i)
                    val strategy = LocationTextExtractionStrategy()
                    val text = PdfTextExtractor.getTextFromPage(page, strategy)
                    stringBuilder.appendLine("--- Page $i ---")
                    stringBuilder.appendLine(text)
                    stringBuilder.appendLine()
                    
                    onProgress((i * 100) / totalPages)
                }
                
                pdfDoc.close()
                
                val outputFile = File(outputDir, "$fileName.txt")
                outputFile.writeText(stringBuilder.toString())
                
                val outputUri = FileUtils.getUriForFile(context, outputFile)
                ConversionResult(
                    true,
                    outputUri,
                    fileName,
                    "Extracted text from $totalPages pages",
                    totalBytes = outputFile.length()
                )
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            ConversionResult(false, null, fileName, e.message ?: "PDF to text failed")
        }
    }
    
    private fun cleanupFiles(files: List<File>) {
        files.forEach { it.delete() }
    }
}
