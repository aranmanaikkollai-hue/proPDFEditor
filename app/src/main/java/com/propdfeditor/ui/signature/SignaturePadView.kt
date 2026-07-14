package com.propdfeditor.ui.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class SignaturePadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var drawPath = Path()
    private var drawPaint = Paint().apply {
        isAntiAlias = true
        isDither = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f
    }
    private var canvasPaint = Paint(Paint.DITHER_FLAG)
    private var drawCanvas: Canvas? = null
    private var canvasBitmap: Bitmap? = null

    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private val touchTolerance = 4f

    var strokeWidth: Float
        get() = drawPaint.strokeWidth
        set(value) {
            drawPaint.strokeWidth = value
        }

    var strokeColor: Int
        get() = drawPaint.color
        set(value) {
            drawPaint.color = value
        }

    var onSignatureChanged: ((Boolean) -> Unit)? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawCanvas = Canvas(canvasBitmap!!)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(canvasBitmap!!, 0f, 0f, canvasPaint)
        canvas.drawPath(drawPath, drawPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val touchX = event.x
        val touchY = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                drawPath.moveTo(touchX, touchY)
                lastTouchX = touchX
                lastTouchY = touchY
                onSignatureChanged?.invoke(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(touchX - lastTouchX)
                val dy = abs(touchY - lastTouchY)
                if (dx >= touchTolerance || dy >= touchTolerance) {
                    drawPath.quadTo(
                        lastTouchX,
                        lastTouchY,
                        (touchX + lastTouchX) / 2,
                        (touchY + lastTouchY) / 2
                    )
                    lastTouchX = touchX
                    lastTouchY = touchY
                }
            }
            MotionEvent.ACTION_UP -> {
                drawPath.lineTo(lastTouchX, lastTouchY)
                drawCanvas?.drawPath(drawPath, drawPaint)
                drawPath.reset()
            }
            else -> return false
        }
        invalidate()
        return true
    }

    fun clear() {
        drawCanvas?.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        drawPath.reset()
        invalidate()
        onSignatureChanged?.invoke(false)
    }

    fun getSignatureBitmap(): Bitmap? {
        val bitmap = canvasBitmap ?: return null
        return bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun isEmpty(): Boolean {
        val bitmap = canvasBitmap ?: return true
        val emptyBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
        val canvas = Canvas(emptyBitmap)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        return bitmap.sameAs(emptyBitmap)
    }
}
