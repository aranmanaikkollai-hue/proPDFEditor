package com.propdf.viewer.annotation.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.propdf.viewer.annotation.model.Annotation
import com.propdf.viewer.annotation.model.AnnotationType
import com.propdf.viewer.annotation.model.ImageStampAnnotation
import com.propdf.viewer.annotation.model.InkAnnotation
import com.propdf.viewer.annotation.model.ShapeAnnotation
import com.propdf.viewer.annotation.model.SignatureAnnotation
import com.propdf.viewer.annotation.model.StickyNoteAnnotation
import com.propdf.viewer.annotation.model.TextCommentAnnotation
import com.propdf.viewer.annotation.model.TextMarkupAnnotation
import kotlin.math.max
import kotlin.math.min

class AnnotationRenderer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun render(
        canvas: Canvas,
        annotations: List<Annotation>,
        pageWidth: Float,
        pageHeight: Float,
        selectedId: String? = null
    ) {
        annotations.sortedBy { ann -> ann.zIndex }.forEach { annotation ->
            canvas.save()
            try {
                when (annotation) {
                    is TextMarkupAnnotation -> renderTextMarkup(canvas, annotation, pageWidth, pageHeight)
                    is InkAnnotation -> renderInk(canvas, annotation, pageWidth, pageHeight)
                    is TextCommentAnnotation -> renderTextComment(canvas, annotation, pageWidth, pageHeight)
                    is StickyNoteAnnotation -> renderStickyNote(canvas, annotation, pageWidth, pageHeight)
                    is ShapeAnnotation -> renderShape(canvas, annotation, pageWidth, pageHeight)
                    is SignatureAnnotation -> renderSignature(canvas, annotation, pageWidth, pageHeight)
                    is ImageStampAnnotation -> renderImageStamp(canvas, annotation, pageWidth, pageHeight)
                }
                if (annotation.id == selectedId) {
                    renderSelectionHandles(canvas, annotation.getBounds(), pageWidth, pageHeight)
                }
            } finally {
                canvas.restore()
            }
        }
    }

    private fun renderTextMarkup(
        canvas: Canvas,
        annotation: TextMarkupAnnotation,
        pageWidth: Float,
        pageHeight: Float
    ) {
        paint.apply {
            color = annotation.color
            alpha = (annotation.alpha * 255).toInt()
            style = Paint.Style.FILL
        }

        val rects = if (annotation.quads.isNotEmpty()) annotation.quads else listOf(annotation.rectBounds)

        rects.forEach { rect ->
            val left = rect.left * pageWidth
            val top = rect.top * pageHeight
            val right = rect.right * pageWidth
            val bottom = rect.bottom * pageHeight

            when (annotation.type) {
                AnnotationType.HIGHLIGHT -> {
                    canvas.drawRect(left, top, right, bottom, paint)
                }
                AnnotationType.UNDERLINE -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = max(2f, pageHeight * 0.003f)
                    canvas.drawLine(left, bottom, right, bottom, paint)
                }
                AnnotationType.STRIKEOUT -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = max(2f, pageHeight * 0.003f)
                    val midY = (top + bottom) / 2
                    canvas.drawLine(left, midY, right, midY, paint)
                }
                else -> {}
            }
        }
    }

    private fun renderInk(
        canvas: Canvas,
        annotation: InkAnnotation,
        pageWidth: Float,
        pageHeight: Float
    ) {
        paint.apply {
            color = annotation.color
            alpha = 255
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        annotation.strokes.forEach { stroke ->
            if (stroke.points.size < 2) return@forEach

            path.reset()
            val first = stroke.points.first()
            path.moveTo(first.x * pageWidth, first.y * pageHeight)

            for (i in 1 until stroke.points.size) {
                val prev = stroke.points[i - 1]
                val curr = stroke.points[i]
                val midX = ((prev.x + curr.x) / 2) * pageWidth
                val midY = ((prev.y + curr.y) / 2) * pageHeight
                path.quadTo(
                    prev.x * pageWidth, prev.y * pageHeight,
                    midX, midY
                )
            }

            val avgPressure = stroke.pressures.average().toFloat().coerceIn(0.3f, 1.0f)
            paint.strokeWidth = annotation.strokeWidth * avgPressure * (pageWidth / 1080f) * 3f

            canvas.drawPath(path, paint)
        }
    }

    private fun renderTextComment(
        canvas: Canvas,
        annotation: TextCommentAnnotation,
        pageWidth: Float,
        pageHeight: Float
    ) {
        val cx = annotation.anchorX * pageWidth
        val cy = annotation.anchorY * pageHeight
        val size = pageWidth * 0.035f

        paint.apply {
            color = annotation.color
            alpha = 255
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, size, paint)

        textPaint.apply {
            color = Color.WHITE
            textSize = size * 1.2f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("N", cx, cy + size * 0.4f, textPaint)

        if (annotation.author.isNotBlank()) {
            textPaint.apply {
                color = Color.parseColor("#666666")
                textSize = size * 0.5f
                typeface = Typeface.DEFAULT
            }
            canvas.drawText(annotation.author, cx, cy + size * 2f, textPaint)
        }
    }

    private fun renderStickyNote(
        canvas: Canvas,
        annotation: StickyNoteAnnotation,
        pageWidth: Float,
        pageHeight: Float
    ) {
        val left = annotation.x * pageWidth
        val top = annotation.y * pageHeight
        val right = left + annotation.width * pageWidth
        val bottom = top + annotation.height * pageHeight

        paint.apply {
            color = Color.BLACK
            alpha = 40
            style = Paint.Style.FILL
        }
        canvas.drawRect(left + 4, top + 4, right + 4, bottom + 4, paint)

        paint.apply {
            color = annotation.color
            alpha = 255
        }
        canvas.drawRect(left, top, right, bottom, paint)

        val foldSize = (right - left) * 0.15f
        path.reset()
        path.moveTo(right - foldSize, top)
        path.lineTo(right, top + foldSize)
        path.lineTo(right, top)
        path.close()
        paint.color = Color.parseColor("#E0E0E0")
        canvas.drawPath(path, paint)

        paint.apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.parseColor("#CCCCCC")
        }
        canvas.drawRect(left, top, right, bottom, paint)

        if (annotation.text.isNotBlank()) {
            textPaint.apply {
                color = Color.BLACK
                textSize = (right - left) * 0.12f
                typeface = Typeface.DEFAULT
            }
            val maxWidth = right - left - 16
            val lines = breakTextIntoLines(annotation.text, textPaint, maxWidth)
            var y = top + textPaint.textSize * 1.5f
            lines.forEach { line ->
                if (y < bottom - textPaint.textSize) {
                    canvas.drawText(line, left + 8, y, textPaint)
                    y += textPaint.textSize * 1.3f
                }
            }
        }
    }

    private fun renderShape(
        canvas: Canvas,
        annotation: ShapeAnnotation,
        pageWidth: Float,
        pageHeight: Float
    ) {
        val left = annotation.rectBounds.left * pageWidth
        val top = annotation.rectBounds.top * pageHeight
        val right = annotation.rectBounds.right * pageWidth
        val bottom = annotation.rectBounds.bottom * pageHeight

        paint.apply {
            color = annotation.color
            alpha = 255
            style = Paint.Style.STROKE
            strokeWidth = annotation.strokeWidth * (pageWidth / 1080f) * 2f
        }

        annotation.fillColor?.let { fill ->
            paint.style = Paint.Style.FILL
            paint.color = fill
            paint.alpha = (annotation.fillAlpha * 255).toInt()
            drawShapeBody(canvas, annotation.type, left, top, right, bottom)
            paint.style = Paint.Style.STROKE
            paint.color = annotation.color
            paint.alpha = 255
        }

        when (annotation.type) {
            AnnotationType.RECTANGLE -> {
                canvas.drawRect(left, top, right, bottom, paint)
            }
            AnnotationType.CIRCLE -> {
                canvas.drawOval(left, top, right, bottom, paint)
            }
            AnnotationType.ARROW -> {
                renderArrow(canvas, left, top, right, bottom, annotation.startArrow, annotation.endArrow)
            }
            else -> {}
        }
    }

    private fun drawShapeBody(
        canvas: Canvas,
        type: AnnotationType,
        left: Float, top: Float, right: Float, bottom: Float
    ) {
        when (type) {
            AnnotationType.RECTANGLE -> canvas.drawRect(left, top, right, bottom, paint)
            AnnotationType.CIRCLE -> canvas.drawOval(left, top, right, bottom, paint)
            else -> {}
        }
    }

    private fun renderArrow(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        startArrow: Boolean, endArrow: Boolean
    ) {
        val startX = if (startArrow) left + (right - left) * 0.1f else left
        val endX = if (endArrow) right - (right - left) * 0.1f else right
        val midY = (top + bottom) / 2

        canvas.drawLine(startX, midY, endX, midY, paint)

        if (endArrow) {
            drawArrowHead(canvas, endX, midY, -1f, 0f, paint.strokeWidth * 3)
        }
        if (startArrow) {
            drawArrowHead(canvas, startX, midY, 1f, 0f, paint.strokeWidth * 3)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun drawArrowHead(
        canvas: Canvas,
        x: Float, y: Float,
        dirX: Float, dirY: Float,
        size: Float
    ) {
        path.reset()
        path.moveTo(x, y)
        path.lineTo(x + dirX * size + size * 0.5f, y - size * 0.5f)
        path.lineTo(x + dirX * size + size * 0.5f, y + size * 0.5f)
        path.close()
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
    }

    private fun renderSignature(
        canvas: Canvas,
        annotation: SignatureAnnotation,
        pageWidth: Float,
        pageHeight: Float
    ) {
        paint.apply {
            color = annotation.color
            alpha = 255
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        annotation.strokes.forEach { stroke ->
            if (stroke.points.size < 2) return@forEach

            path.reset()
            val first = stroke.points.first()
            path.moveTo(first.x * pageWidth, first.y * pageHeight)

            for (i in 1 until stroke.points.size) {
                val prev = stroke.points[i - 1]
                val curr = stroke.points[i]
                val midX = ((prev.x + curr.x) / 2) * pageWidth
                val midY = ((prev.y + curr.y) / 2) * pageHeight
                path.quadTo(
                    prev.x * pageWidth, prev.y * pageHeight,
                    midX, midY
                )
            }

            paint.strokeWidth = annotation.strokeWidth * (pageWidth / 1080f) * 2f
            canvas.drawPath(path, paint)
        }
    }

    private fun renderImageStamp(
        canvas: Canvas,
        annotation: ImageStampAnnotation,
        pageWidth: Float,
        pageHeight: Float
    ) {
        val left = annotation.x * pageWidth
        val top = annotation.y * pageHeight
        val right = left + annotation.width * pageWidth
        val bottom = top + annotation.height * pageHeight

        paint.apply {
            color = Color.parseColor("#CCCCCC")
            alpha = 100
            style = Paint.Style.FILL
        }
        canvas.drawRect(left, top, right, bottom, paint)

        paint.apply {
            color = Color.parseColor("#999999")
            alpha = 255
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRect(left, top, right, bottom, paint)

        textPaint.apply {
            color = Color.parseColor("#666666")
            textSize = (right - left) * 0.1f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("[Image]", (left + right) / 2, (top + bottom) / 2, textPaint)
    }

    private fun renderSelectionHandles(
        canvas: Canvas,
        bounds: RectF,
        pageWidth: Float,
        pageHeight: Float
    ) {
        val left = bounds.left * pageWidth
        val top = bounds.top * pageHeight
        val right = bounds.right * pageWidth
        val bottom = bounds.bottom * pageHeight
        val handleSize = pageWidth * 0.015f

        paint.apply {
            color = Color.parseColor("#448AFF")
            alpha = 255
            style = Paint.Style.FILL
        }

        val corners = listOf(
            left to top, right to top,
            left to bottom, right to bottom
        )
        corners.forEach { (x, y) ->
            canvas.drawCircle(x, y, handleSize, paint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRect(left, top, right, bottom, paint)
    }

    private fun breakTextIntoLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            var breakIndex = remaining.length
            while (breakIndex > 0 && paint.measureText(remaining.substring(0, breakIndex)) > maxWidth) {
                breakIndex--
            }
            if (breakIndex == 0) breakIndex = 1
            lines.add(remaining.substring(0, breakIndex))
            remaining = remaining.substring(breakIndex).trimStart()
        }
        return lines
    }
}
