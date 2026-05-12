package com.propdf.editor.ui.annotations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

class AnnotatedPageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val imageView = ImageView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val canvasView = AnnotationCanvasView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    init {
        addView(imageView)
        addView(canvasView)
    }

    fun setPageBitmap(bitmap: Bitmap?) {
        imageView.setImageBitmap(bitmap)
    }

    fun getAnnotationCanvas(): AnnotationCanvasView = canvasView

    fun renderToBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        imageView.draw(canvas)
        canvasView.draw(canvas)
        return bmp
    }
}
