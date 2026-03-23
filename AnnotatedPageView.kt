package com.propdf.editor.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar

class AnnotatedPageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    val pageImageView : ImageView
    val annotationLayer : AnnotationCanvasView
    private val spinner : ProgressBar

    init {
        pageImageView = ImageView(context).apply {
            layoutParams = LayoutParams(-1, -2)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
        }
        annotationLayer = AnnotationCanvasView(context).apply {
            layoutParams = LayoutParams(-1, -1)
            setTool(AnnotationCanvasView.TOOL_NONE, Color.BLUE)
        }
        spinner = ProgressBar(context).apply {
            layoutParams = LayoutParams(-2, -2).also {
                (it as LayoutParams).gravity = Gravity.CENTER
            }
        }
        elevation = 3f
        addView(pageImageView)
        addView(annotationLayer)
        addView(spinner)
    }

    fun showBitmap(bmp: Bitmap) {
        pageImageView.setImageBitmap(bmp)
        spinner.visibility = GONE
    }

    fun recycle() {
        (pageImageView.drawable as? android.graphics.drawable.BitmapDrawable)
            ?.bitmap?.takeIf { !it.isRecycled }?.recycle()
        pageImageView.setImageBitmap(null)
        spinner.visibility = VISIBLE
        annotationLayer.clearAll()
        annotationLayer.release()
    }

    fun setTool(tool: String, color: Int, width: Float = 6f) =
        annotationLayer.setTool(tool, color, width)

    fun undoAnnotation()       = annotationLayer.undo()
    fun clearAnnotations()     = annotationLayer.clearAll()
    fun hasAnnotations()       = annotationLayer.hasAnnotations()
    fun getAnnotationStrokes() = annotationLayer.getStrokes()
}
