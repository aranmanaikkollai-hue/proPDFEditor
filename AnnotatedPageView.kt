package com.propdf.editor.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.view.Gravity

/**
 * AnnotatedPageView — One PDF page + its annotation layer.
 *
 * Layout (FrameLayout stack, bottom → top):
 *   [ImageView: rendered PDF page]
 *   [AnnotationCanvasView: touch-driven drawing overlay]
 *   [ProgressBar: visible while page is rendering]
 *
 * The annotation canvas is exactly the same size as the ImageView,
 * so touch coordinates in the canvas map directly to image coordinates.
 * Caller must account for the render scale when mapping to PDF coords.
 */
class AnnotatedPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    val pageImageView   : ImageView
    val annotationLayer : AnnotationCanvasView
    private val loadingBar: ProgressBar

    init {
        // ── PDF page image ────────────────────────────────────
        pageImageView = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            // White background so transparent PDF pages look correct
            setBackgroundColor(android.graphics.Color.WHITE)
        }

        // ── Annotation overlay (same size as image) ───────────
        annotationLayer = AnnotationCanvasView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            // Disabled until a tool is selected
            setTool(AnnotationCanvasView.TOOL_NONE, android.graphics.Color.BLUE)
        }

        // ── Loading spinner (centered) ────────────────────────
        loadingBar = ProgressBar(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).also { (it as LayoutParams).gravity = Gravity.CENTER }
            visibility = VISIBLE
        }

        addView(pageImageView)
        addView(annotationLayer)
        addView(loadingBar)

        // Slight elevation shadow between pages
        elevation = 2f
    }

    // ── Page content ──────────────────────────────────────────

    /** Show the rendered bitmap. Hides the loading spinner. */
    fun showBitmap(bitmap: Bitmap) {
        pageImageView.setImageBitmap(bitmap)
        loadingBar.visibility = GONE
    }

    /** Called when this ViewHolder is recycled — free bitmap memory. */
    fun recycle() {
        // Detach bitmap from ImageView before recycling
        (pageImageView.drawable as? android.graphics.drawable.BitmapDrawable)
            ?.bitmap
            ?.takeIf { !it.isRecycled }
            ?.recycle()
        pageImageView.setImageBitmap(null)
        loadingBar.visibility = VISIBLE
        annotationLayer.clearAll()
        annotationLayer.release()
    }

    // ── Annotation delegation ─────────────────────────────────

    fun setTool(tool: String, color: Int, widthPx: Float = 6f) {
        annotationLayer.setTool(tool, color, widthPx)
        // Only intercept touches when a tool is active
        annotationLayer.isEnabled = tool != AnnotationCanvasView.TOOL_NONE
    }

    fun undoAnnotation() = annotationLayer.undo()
    fun redoAnnotation() = annotationLayer.redo()
    fun clearAnnotations() = annotationLayer.clearAll()
    fun hasAnnotations() = annotationLayer.hasAnnotations()
    fun getAnnotationStrokes() = annotationLayer.getStrokes()
}
