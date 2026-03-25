package com.propdf.editor.ui.viewer


import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View


class AnnotatedPageView(context: Context) : View(context) {


    private var bitmap: Bitmap? = null


    private val paint = Paint().apply {
        color = Color.RED
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }


    private val path = Path()


    fun setPageBitmap(bmp: Bitmap) {
        bitmap = bmp
        invalidate()
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)


        bitmap?.let {
            val scaled = Bitmap.createScaledBitmap(it, width, height, true)
            canvas.drawBitmap(scaled, 0f, 0f, null)
        }


        canvas.drawPath(path, paint)
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> path.moveTo(event.x, event.y)
            MotionEvent.ACTION_MOVE -> path.lineTo(event.x, event.y)
        }
        invalidate()
        return true
    }
}