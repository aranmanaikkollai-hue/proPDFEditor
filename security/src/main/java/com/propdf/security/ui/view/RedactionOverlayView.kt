// security/src/main/java/com/propdf/security/ui/view/RedactionOverlayView.kt
package com.propdf.security.ui.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class RedactionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(128, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isDrawing = false

    private val redactions = mutableListOf<RectF>()
    private var onRedactionAdded: ((RectF) -> Unit)? = null

    fun setOnRedactionAddedListener(listener: (RectF) -> Unit) {
        onRedactionAdded = listener
    }

    fun addRedaction(rect: RectF) {
        redactions.add(rect)
        invalidate()
    }

    fun clearRedactions() {
        redactions.clear()
        invalidate()
    }

    fun getRedactions(): List<RectF> = redactions.toList()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                currentX = startX
                currentY = startY
                isDrawing = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentX = event.x
                currentY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentX = event.x
                currentY = event.y
                isDrawing = false
                
                val rect = RectF(
                    min(startX, currentX),
                    min(startY, currentY),
                    max(startX, currentX),
                    max(startY, currentY)
                )
                
                if (rect.width() > 20 && rect.height() > 20) {
                    redactions.add(rect)
                    onRedactionAdded?.invoke(rect)
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw existing redactions
        redactions.forEach { rect ->
            canvas.drawRect(rect, paint)
            canvas.drawRect(rect, borderPaint)
            canvas.drawText(
                "REDACTED",
                rect.centerX(),
                rect.centerY(),
                textPaint
            )
        }

        // Draw current selection
        if (isDrawing) {
            val rect = RectF(
                min(startX, currentX),
                min(startY, currentY),
                max(startX, currentX),
                max(startY, currentY)
            )
            canvas.drawRect(rect, paint)
            canvas.drawRect(rect, borderPaint)
        }
    }
}
