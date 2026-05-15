package com.propdf.viewer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.propdf.viewer.R

/**
 * Custom page scrubber for fast page jumping.
 * Shows page number overlay while scrubbing.
 * Features smooth track, progress indicator, and animated thumb.
 */
class PageScrubberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface ScrubberListener {
        fun onPageSelected(pageIndex: Int)
        fun onScrubbingStart()
        fun onScrubbingEnd()
    }

    var totalPages: Int = 0
    var currentPage: Int = 0
        set(value) {
            field = value.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
            invalidate()
        }

    var listener: ScrubberListener? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scrubber_track)
        style = Paint.Style.FILL
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scrubber_progress)
        style = Paint.Style.FILL
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scrubber_thumb)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scrubber_text)
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    private val trackRect = RectF()
    private var isDragging = false
    private var thumbRadius = 20f
    private var trackHeight = 8f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (totalPages <= 0) return

        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2

        // Draw track background
        trackRect.set(
            thumbRadius,
            centerY - trackHeight / 2,
            width - thumbRadius,
            centerY + trackHeight / 2
        )
        canvas.drawRoundRect(trackRect, trackHeight / 2, trackHeight / 2, trackPaint)

        // Draw progress
        val progressWidth = if (totalPages > 1) {
            trackRect.width() * (currentPage.toFloat() / (totalPages - 1))
        } else 0f
        val progressRect = RectF(
            trackRect.left,
            trackRect.top,
            trackRect.left + progressWidth,
            trackRect.bottom
        )
        canvas.drawRoundRect(progressRect, trackHeight / 2, trackHeight / 2, progressPaint)

        // Draw thumb
        val thumbX = trackRect.left + progressWidth
        canvas.drawCircle(thumbX, centerY, thumbRadius, thumbPaint)

        // Draw page number while dragging
        if (isDragging) {
            canvas.drawText(
                "${currentPage + 1}",
                thumbX,
                centerY - thumbRadius - 10,
                textPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                listener?.onScrubbingStart()
                updatePageFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    updatePageFromTouch(event.x)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                listener?.onScrubbingEnd()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updatePageFromTouch(x: Float) {
        val width = width.toFloat()
        val progress = ((x - thumbRadius) / (width - 2 * thumbRadius)).coerceIn(0f, 1f)
        val newPage = (progress * (totalPages - 1)).toInt().coerceIn(0, totalPages - 1)
        if (newPage != currentPage) {
            currentPage = newPage
            listener?.onPageSelected(newPage)
            invalidate()
        }
    }
}
