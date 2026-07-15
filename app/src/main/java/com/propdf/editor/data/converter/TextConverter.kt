package com.propdf.editor.data.converter

import android.content.Context
import android.net.Uri
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.properties.TextAlignment
import com.propdf.editor.domain.model.ConversionResult
import com.propdf.editor.utils.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class TextConverter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val FONT_SIZE = 12f
        private const val MARGIN = 36f
        private const val LINE_HEIGHT = 14f
    }
    
    suspend fun toPdf(
        context: Context,
        sourceUri: Uri,
        outputDir: File,
        fileName: String,
        onProgress: suspend (Int) -> Unit
    ): ConversionResult = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: return@withContext ConversionResult(false, null, fileName, "Cannot read text file")
            
            val text = inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    reader.readText()
                }
            }
            
            if (!coroutineContext.isActive) {
                return@withContext ConversionResult(false, null, fileName, "Cancelled")
            }
            
            val outputFile = File(outputDir, "$fileName.pdf")
            val writer = PdfWriter(outputFile.absolutePath)
            val pdfDoc = PdfDocument(writer)
            val document = Document(pdfDoc, PageSize.A4)
            
            document.setMargins(MARGIN, MARGIN, MARGIN, MARGIN)
            
            val font = PdfFontFactory.createFont(com.itextpdf.io.font.constants.StandardFonts.HELVETICA)
            
            // Split text into paragraphs and add
            val paragraphs = text.split(Regex("\n{2,}"))
            val totalParagraphs = paragraphs.size
            
            paragraphs.forEachIndexed { index, para ->
                if (!coroutineContext.isActive) {
                    document.close()
                    pdfDoc.close()
                    outputFile.delete()
                    return@withContext ConversionResult(false, null, fileName, "Cancelled")
                }
                
                val paragraph = Paragraph(para.trim())
                    .setFont(font)
                    .setFontSize(FONT_SIZE)
                    .setTextAlignment(TextAlignment.LEFT)
                    .setMultipliedLeading(1.2f)
                
                document.add(paragraph)
                
                // Add spacing between paragraphs
                if (index < paragraphs.size - 1) {
                    document.add(Paragraph(" ").setFontSize(FONT_SIZE))
                }
                
                onProgress(((index + 1) * 100) / totalParagraphs)
            }
            
            document.close()
            pdfDoc.close()
            
            val outputUri = FileUtils.getUriForFile(context, outputFile)
            ConversionResult(
                true,
                outputUri,
                fileName,
                "Converted text to PDF",
                totalBytes = outputFile.length()
            )
            
        } catch (e: Exception) {
            ConversionResult(false, null, fileName, e.message ?: "Text to PDF failed")
        }
    }
}
