package com.propdf.viewer.annotation.render

import android.graphics.Canvas
import com.propdf.viewer.annotation.manager.AnnotationManager

/**
 * Unified annotation renderer that draws annotations directly onto the PDF canvas
 * using the shared transformation matrix. Eliminates overlay desynchronization.
 *
 * Must be called inside the same canvas save/restore block as the PDF bitmap,
 * after [Canvas.concat] with the view transform matrix.
 */
class UnifiedAnnotationRenderer(
    private val delegate: AnnotationRenderer = AnnotationRenderer()
) {

    /**
     * Render annotations for the given page.
     *
     * @param canvas The canvas already transformed to PDF bitmap space
     * @param annotationManager Source of annotations and selection state
     * @param pageIndex Which page to render
     * @param bitmapWidth Width of the PDF bitmap in pixels
     * @param bitmapHeight Height of the PDF bitmap in pixels
     */
    fun render(
        canvas: Canvas,
        annotationManager: AnnotationManager,
        pageIndex: Int,
        bitmapWidth: Float,
        bitmapHeight: Float
    ) {
        val annotations = annotationManager.getAnnotationsForPage(pageIndex)
        if (annotations.isEmpty()) return

        delegate.render(
            canvas = canvas,
            annotations = annotations,
            pageWidth = bitmapWidth,
            pageHeight = bitmapHeight,
            selectedId = annotationManager.selectedAnnotationId
        )
    }
}
