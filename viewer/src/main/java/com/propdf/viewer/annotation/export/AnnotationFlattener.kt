package com.propdf.viewer.annotation.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import com.propdf.viewer.annotation.manager.AnnotationManager
import com.propdf.viewer.annotation.render.AnnotationRenderer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AnnotationFlattener(private val context: Context) {

    private val renderer = AnnotationRenderer()

    suspend fun flattenAnnotations(
        sourcePdfUri: Uri,
        annotationManager: AnnotationManager,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            PDFBoxResourceLoader.init(context)

            val sourceFile = File(sourcePdfUri.path ?: return@withContext false)
            val document = PDDocument.load(sourceFile)

            val pages = document.pages
            for (pageIndex in 0 until pages.count) {
                val page = pages.get(pageIndex)
                val annotations = annotationManager.getAnnotationsForPage(pageIndex)
                if (annotations.isEmpty()) continue

                val mediaBox = page.mediaBox
                val pageWidth = mediaBox.width
                val pageHeight = mediaBox.height

                val bitmap = Bitmap.createBitmap(
                    (pageWidth * 2).toInt(),
                    (pageHeight * 2).toInt(),
                    Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(bitmap)
                canvas.scale(2f, 2f)

                renderer.render(
                    canvas = canvas,
                    annotations = annotations,
                    pageWidth = pageWidth,
                    pageHeight = pageHeight
                )

                val pdImage = LosslessFactory.createFromImage(document, bitmap)
                val contentStream = PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)
                contentStream.drawImage(pdImage, 0f, 0f, pageWidth, pageHeight)
                contentStream.close()
                bitmap.recycle()
            }

            document.save(FileOutputStream(outputFile))
            document.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun exportAnnotationLayer(
        annotationManager: AnnotationManager,
        pageCount: Int,
        pageWidth: Float,
        pageHeight: Float,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val document = PDDocument()

            for (pageIndex in 0 until pageCount) {
                val page = PDPage(PDRectangle(pageWidth, pageHeight))
                document.addPage(page)

                val annotations = annotationManager.getAnnotationsForPage(pageIndex)
                if (annotations.isEmpty()) continue

                val bitmap = Bitmap.createBitmap(
                    (pageWidth * 2).toInt(),
                    (pageHeight * 2).toInt(),
                    Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(bitmap)
                canvas.scale(2f, 2f)

                renderer.render(canvas, annotations, pageWidth, pageHeight)

                val pdImage = LosslessFactory.createFromImage(document, bitmap)
                val contentStream = PDPageContentStream(document, page)
                contentStream.drawImage(pdImage, 0f, 0f, pageWidth, pageHeight)
                contentStream.close()
                bitmap.recycle()
            }

            document.save(FileOutputStream(outputFile))
            document.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
