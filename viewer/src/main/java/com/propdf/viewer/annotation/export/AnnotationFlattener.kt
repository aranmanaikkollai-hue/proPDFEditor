package com.propdf.viewer.annotation.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import com.propdf.viewer.annotation.manager.AnnotationManager
import com.propdf.viewer.annotation.render.AnnotationRenderer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AnnotationFlattener {

    suspend fun flattenAnnotations(
        pdfUri: Uri,
        annotationManager: AnnotationManager,
        outputFile: File
    ) = withContext(Dispatchers.IO) {
        val sourceFile = File(pdfUri.path ?: throw IllegalArgumentException("Invalid URI"))
        PDDocument.load(sourceFile).use { document ->
            val renderer = AnnotationRenderer()
            for (i in 0 until document.numberOfPages) {
                val page = document.getPage(i)
                val annotations = annotationManager.getAnnotationsForPage(i)
                if (annotations.isNotEmpty()) {
                    val mediaBox = page.mediaBox
                    val width = mediaBox.width.toInt()
                    val height = mediaBox.height.toInt()
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                    renderer.render(canvas, annotations, width.toFloat(), height.toFloat())
                    @Suppress("UNUSED_VARIABLE")
                    val pdImage = com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document, bitmap)
                    // TODO: Add pdImage to page content stream
                    // This is a simplified implementation
                    bitmap.recycle()
                }
            }
            document.save(outputFile)
        }
    }
}
