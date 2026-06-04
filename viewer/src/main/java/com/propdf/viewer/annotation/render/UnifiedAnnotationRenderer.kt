package com.propdf.viewer.annotation.render

import android.graphics.Canvas
import com.propdf.viewer.annotation.manager.AnnotationManager
import com.propdf.viewer.annotation.model.ViewerTheme

class UnifiedAnnotationRenderer {

    private val renderer = AnnotationRenderer()

    fun render(
        canvas: Canvas,
        annotationManager: AnnotationManager,
        pageIndex: Int,
        pageWidth: Float,
        pageHeight: Float
    ) {
        val annotations = annotationManager.getAnnotationsForPage(pageIndex)
        val selectedId = annotationManager.selectedAnnotationId
        renderer.render(canvas, annotations, pageWidth, pageHeight, selectedId)
    }
}
