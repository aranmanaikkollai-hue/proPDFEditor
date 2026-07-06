package com.propdf.editor.ui.ocr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OcrCropOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var cropRect = Rect()
    private val paint = Paint().apply { color = Color.parseColor("#2196F3"); strokeWidth = 4f; style = Paint.Style.STROKE }
    private val fillPaint = Paint().apply { color = Color.parseColor("#1A2196F3"); style = Paint.Style.FILL }
    private val cornerPaint = Paint().apply { color = Color.WHITE; strokeWidth = 6f; style = Paint.Style.STROKE }

    fun setCropRect(rect: Rect) { cropRect = rect; invalidate() }
    fun getCropRect(): Rect = cropRect

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cropRect.isEmpty) return
        val rectF = RectF(cropRect)
        canvas.drawRect(rectF, fillPaint)
        canvas.drawRect(rectF, paint)
        listOf(rectF.left to rectF.top, rectF.right to rectF.top, rectF.right to rectF.bottom, rectF.left to rectF.bottom)
            .forEach { (x, y) -> canvas.drawCircle(x, y, 20f, cornerPaint) }
    }
}
