package com.propdfeditor.scanner.presentation.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.propdfeditor.scanner.domain.model.EdgeDetectionResult
import com.propdfeditor.scanner.domain.model.PointF

/**
 * Custom view that draws detected document edges over camera preview.
 * Lightweight - only draws lines, no bitmap processing.
 *
 * Features:
 * - Real-time edge polygon with corner markers
 * - Color-coded confidence (green > 70%, orange < 70%)
 * - Document guide overlay (dashed rectangle)
 * - Smooth animation support via invalidate()
 */
class EdgeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentResult: EdgeDetectionResult? = null
    private val path = Path()

    private val edgePaint = Paint().apply {
        color = 0xFF00FF00.toInt() // Green
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
    }

    private val cornerPaint = Paint().apply {
        color = 0xFF00FF00.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val cornerInnerPaint = Paint().apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val guidePaint = Paint().apply {
        color = 0x66FFFFFF.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }

    private val lowConfidencePaint = Paint().apply {
        color = 0xFFFFA500.toInt() // Orange for low confidence
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
    }

    private val lowConfidenceCornerPaint = Paint().apply {
        color = 0xFFFFA500.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 36f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    /**
     * Update edge detection result and trigger redraw.
     */
    fun updateEdgeResult(result: EdgeDetectionResult) {
        currentResult = result
        invalidate()
    }

    /**
     * Clear edges from view.
     */
    fun clearEdges() {
        currentResult = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw document guide (dashed rectangle in center)
        drawDocumentGuide(canvas)

        val result = currentResult ?: return
        if (result.corners.size != 4) return

        val isHighConfidence = result.confidence > 70f
        val linePaint = if (isHighConfidence) edgePaint else lowConfidencePaint
        val dotPaint = if (isHighConfidence) cornerPaint else lowConfidenceCornerPaint

        // Draw polygon connecting corners
        path.reset()
        val first = scalePoint(result.corners[0])
        path.moveTo(first.x, first.y)

        for (i in 1 until result.corners.size) {
            val pt = scalePoint(result.corners[i])
            path.lineTo(pt.x, pt.y)
        }
        path.close()
        canvas.drawPath(path, linePaint)

        // Draw corner markers with inner dot
        result.corners.forEach { corner ->
            val pt = scalePoint(corner)
            // Outer circle
            canvas.drawCircle(pt.x, pt.y, 14f, dotPaint)
            // Inner black dot
            canvas.drawCircle(pt.x, pt.y, 8f, cornerInnerPaint)
        }

        // Draw confidence label if low
        if (!isHighConfidence && result.confidence > 0) {
            canvas.drawText(
                "Move closer",
                width / 2f,
                height * 0.85f,
                labelPaint
            )
        }
    }

    private fun drawDocumentGuide(canvas: Canvas) {
        val marginX = width * 0.08f
        val marginY = height * 0.12f
        canvas.drawRect(
            marginX,
            marginY,
            width - marginX,
            height - marginY,
            guidePaint
        )
    }

    /**
     * Scale normalized points (0-1) to view coordinates.
     * Points are stored as ratios of image dimensions.
     */
    private fun scalePoint(point: PointF): android.graphics.PointF {
        return android.graphics.PointF(
            point.x * width,
            point.y * height
        )
    }
}
