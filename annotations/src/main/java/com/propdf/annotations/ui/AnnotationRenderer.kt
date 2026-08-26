package com.propdf.annotations.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.DashPathEffect
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.propdf.annotations.model.*
import com.propdf.annotations.model.Annotation
import kotlin.math.*

/**
 * Canvas renderer for all annotation types.
 * Renders annotations in PDF page coordinates with proper scaling.
 *
 * Performance optimizations:
 * - Caches Paint objects per annotation
 * - Clips to annotation bounds
 * - Uses hardware-accelerated paths
 *
 * Note: this file intentionally mixes two rendering styles:
 * - android.graphics.Paint/Path (aliased as AndroidPaint/AndroidPath) for
 *   operations that go through DrawScope.drawIntoCanvas { it.nativeCanvas... }
 * - androidx.compose.ui.graphics primitives (Color, Path, Stroke, Fill) for
 *   native Compose draw calls (drawPath, drawLine, drawRoundRect, etc.)
 */
class AnnotationRenderer(private val context: Context? = null) {

    // Custom image stamps previously had no rendering path at all -- getDisplayText()
    // for CUSTOM_IMAGE just returns the stamp's name string, so a placed image stamp
    // silently rendered as an empty/blank text box. Decoding is bounded (max 800px on
    // the long edge) and cached per imagePath so it only happens once, not once per
    // recomposition/frame -- decoding a full-resolution gallery photo on every draw call
    // would be a real jank/OOM risk.
    private val imageBitmapCache = mutableMapOf<String, ImageBitmap?>()

    private fun loadStampImage(imagePath: String): ImageBitmap? {
        imageBitmapCache[imagePath]?.let { return it }
        if (imageBitmapCache.containsKey(imagePath)) return null // cached failure
        val ctx = context ?: return null
        val bitmap = try {
            val uri = Uri.parse(imagePath)
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > 800) sampleSize *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
        } catch (e: Exception) {
            null
        }
        val imageBitmap = bitmap?.asImageBitmap()
        imageBitmapCache[imagePath] = imageBitmap
        return imageBitmap
    }

    private val paintCache = mutableMapOf<String, AndroidPaint>()
    private val pathCache = mutableMapOf<String, AndroidPath>()

    /**
     * Render an annotation on the canvas.
     * This is the public entry point - call from within a DrawScope block.
     */
    fun render(
        drawScope: DrawScope,
        annotation: Annotation,
        pageScale: Float,
        pageOffset: Offset
    ) {
        with(drawScope) {
            renderAnnotation(annotation, pageScale, pageOffset)
        }
    }

    /**
     * Render an annotation on the canvas.
     * Must be called from within a DrawScope block (e.g. inside Canvas draw lambda).
     */
    fun DrawScope.renderAnnotation(
        annotation: Annotation,
        pageScale: Float,
        pageOffset: Offset
    ) {
        if (!annotation.isVisible) return

        val alpha = (annotation.opacity * 255).toInt()

        when (annotation) {
            is HighlightAnnotation -> renderHighlight(annotation, pageScale, pageOffset, alpha)
            is ShapeAnnotation -> renderShape(annotation, pageScale, pageOffset, alpha)
            is StrokeAnnotation -> renderStroke(annotation, pageScale, pageOffset, alpha)
            is TextAnnotation -> renderText(annotation, pageScale, pageOffset, alpha)
            is StampAnnotation -> renderStamp(annotation, pageScale, pageOffset, alpha)
            is LassoAnnotation -> renderLasso(annotation, pageScale, pageOffset)
        }

        if (annotation.isSelected) {
            renderSelectionHandles(annotation, pageScale, pageOffset)
        }
    }

    private fun DrawScope.renderHighlight(
        annotation: HighlightAnnotation,
        pageScale: Float,
        pageOffset: Offset,
        alpha: Int
    ) {
        // Paint is cached per-annotation-id for allocation reuse across frames, but its
        // color/alpha are re-applied on every call below. Annotation ids are stable across
        // copy()-based edits (color/opacity change via the toolbar keeps the same id), so a
        // create-once cache here previously froze the HIGHLIGHT fill at whatever color/alpha
        // it had the first time it was rendered -- editing a selected highlight's color or
        // opacity updated the model but the canvas kept drawing the stale cached Paint.
        val paint = paintCache.getOrPut(annotation.id) { AndroidPaint().apply { style = AndroidPaint.Style.FILL } }
        paint.color = annotation.color
        paint.alpha = alpha

        annotation.rects.forEach { rect ->
            val screenRect = rect.toScreen(pageScale, pageOffset)

            when (annotation.highlightType) {
                HighlightAnnotation.HighlightType.HIGHLIGHT -> {
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawRect(screenRect, paint)
                    }
                }
                HighlightAnnotation.HighlightType.UNDERLINE -> {
                    val y = screenRect.bottom - 2f
                    drawLine(
                        color = Color(annotation.color),
                        start = Offset(screenRect.left, y),
                        end = Offset(screenRect.right, y),
                        strokeWidth = (2.5f * pageScale).coerceAtLeast(3f),
                        alpha = annotation.opacity
                    )
                }
                HighlightAnnotation.HighlightType.STRIKEOUT -> {
                    val y = screenRect.centerY()
                    drawLine(
                        color = Color(annotation.color),
                        start = Offset(screenRect.left, y),
                        end = Offset(screenRect.right, y),
                        strokeWidth = (2.5f * pageScale).coerceAtLeast(3f),
                        alpha = annotation.opacity
                    )
                }
                HighlightAnnotation.HighlightType.SQUIGGLY -> {
                    drawSquigglyLine(
                        screenRect.left, screenRect.right, screenRect.bottom - 2f,
                        annotation.color, pageScale, annotation.opacity
                    )
                }
            }
        }
    }

    private fun DrawScope.renderShape(
        annotation: ShapeAnnotation,
        pageScale: Float,
        pageOffset: Offset,
        alpha: Int
    ) {
        val strokeWidth = annotation.strokeWidth * pageScale
        val color = Color(annotation.color)

        // As with the highlight paint above, these are cached by id purely for object reuse --
        // every property that can change on a live/selected shape (color, opacity, stroke
        // width, dash pattern) is reapplied below on every render call. Previously these were
        // baked in only at first creation, so using the toolbar to change a selected shape's
        // color, opacity, or stroke width silently did nothing on screen even though the
        // annotation model had genuinely updated (Phase 3 "changes must immediately appear on
        // canvas" requirement).
        val paint = paintCache.getOrPut(annotation.id + "_stroke") {
            AndroidPaint().apply {
                style = AndroidPaint.Style.STROKE
                isAntiAlias = true
            }
        }
        paint.color = annotation.color
        paint.alpha = alpha
        paint.strokeWidth = annotation.strokeWidth
        paint.pathEffect = if (annotation.isDashed) {
            DashPathEffect(annotation.dashPattern.map { it * pageScale }.toFloatArray(), 0f)
        } else {
            null
        }

        val fillPaint = annotation.fillColor?.let { fillColor ->
            paintCache.getOrPut(annotation.id + "_fill") {
                AndroidPaint().apply {
                    style = AndroidPaint.Style.FILL
                    isAntiAlias = true
                }
            }.also {
                it.color = fillColor
                it.alpha = alpha
            }
        }

        when (annotation.shapeType) {
            ShapeAnnotation.ShapeType.RECTANGLE -> {
                val rect = annotation.rect.toScreen(pageScale, pageOffset)
                fillPaint?.let { fp -> drawIntoCanvas { canvas -> canvas.nativeCanvas.drawRect(rect, fp) } }
                drawIntoCanvas { canvas -> canvas.nativeCanvas.drawRect(rect, paint) }
            }
            ShapeAnnotation.ShapeType.CIRCLE -> {
                val rect = annotation.rect.toScreen(pageScale, pageOffset)
                fillPaint?.let { fp -> drawIntoCanvas { canvas -> canvas.nativeCanvas.drawOval(rect, fp) } }
                drawIntoCanvas { canvas -> canvas.nativeCanvas.drawOval(rect, paint) }
            }
            ShapeAnnotation.ShapeType.LINE -> {
                val start = Offset(
                    annotation.rect.left * pageScale + pageOffset.x,
                    annotation.rect.top * pageScale + pageOffset.y
                )
                val end = Offset(
                    annotation.rect.right * pageScale + pageOffset.x,
                    annotation.rect.bottom * pageScale + pageOffset.y
                )
                drawLine(
                    color = color,
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth,
                    alpha = annotation.opacity
                )
            }
            ShapeAnnotation.ShapeType.ARROW -> {
                val start = Offset(
                    annotation.rect.left * pageScale + pageOffset.x,
                    annotation.rect.top * pageScale + pageOffset.y
                )
                val end = Offset(
                    annotation.rect.right * pageScale + pageOffset.x,
                    annotation.rect.bottom * pageScale + pageOffset.y
                )
                drawLine(
                    color = color,
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth,
                    alpha = annotation.opacity
                )
                drawArrowHead(start, end, color, strokeWidth * 3, annotation.opacity)
            }
            ShapeAnnotation.ShapeType.POLYGON -> {
                if (annotation.vertices.size >= 3) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        val first = annotation.vertices[0]
                        moveTo(first.x * pageScale + pageOffset.x, first.y * pageScale + pageOffset.y)
                        annotation.vertices.drop(1).forEach {
                            lineTo(it.x * pageScale + pageOffset.x, it.y * pageScale + pageOffset.y)
                        }
                        close()
                    }
                    fillPaint?.let { fp -> drawPath(path, Color(fp.color), style = Fill, alpha = annotation.opacity) }
                    drawPath(path, color, style = Stroke(strokeWidth), alpha = annotation.opacity)
                }
            }
            ShapeAnnotation.ShapeType.CLOUD -> {
                if (annotation.vertices.size >= 3) {
                    val path = createCloudPath(annotation.vertices, pageScale, pageOffset)
                    fillPaint?.let { fp -> drawPath(path, Color(fp.color), style = Fill, alpha = annotation.opacity * 0.1f) }
                    drawPath(path, color, style = Stroke(strokeWidth), alpha = annotation.opacity)
                }
            }
        }

        if (annotation.startArrow && annotation.shapeType == ShapeAnnotation.ShapeType.LINE) {
            val start = Offset(
                annotation.rect.left * pageScale + pageOffset.x,
                annotation.rect.top * pageScale + pageOffset.y
            )
            val end = Offset(
                annotation.rect.right * pageScale + pageOffset.x,
                annotation.rect.bottom * pageScale + pageOffset.y
            )
            drawArrowHead(end, start, color, strokeWidth * 3, annotation.opacity)
        }
        if (annotation.endArrow && annotation.shapeType == ShapeAnnotation.ShapeType.LINE) {
            val start = Offset(
                annotation.rect.left * pageScale + pageOffset.x,
                annotation.rect.top * pageScale + pageOffset.y
            )
            val end = Offset(
                annotation.rect.right * pageScale + pageOffset.x,
                annotation.rect.bottom * pageScale + pageOffset.y
            )
            drawArrowHead(start, end, color, strokeWidth * 3, annotation.opacity)
        }
    }

    private fun DrawScope.renderStroke(
        annotation: StrokeAnnotation,
        pageScale: Float,
        pageOffset: Offset,
        alpha: Int
    ) {
        val points = annotation.getRenderPoints()
        if (points.size < 2) return

        when (annotation.penType) {
            StrokeAnnotation.PenType.INK,
            StrokeAnnotation.PenType.SIGNATURE -> {
                drawVariableWidthStroke(points, annotation, pageScale, pageOffset, alpha)
            }
            StrokeAnnotation.PenType.CALLIGRAPHY -> {
                drawCalligraphyStroke(points, annotation, pageScale, pageOffset, alpha)
            }
            StrokeAnnotation.PenType.MARKER -> {
                drawMarkerStroke(points, annotation, pageScale, pageOffset, alpha)
            }
            StrokeAnnotation.PenType.PENCIL -> {
                drawPencilStroke(points, annotation, pageScale, pageOffset, alpha)
            }
            StrokeAnnotation.PenType.ERASER -> {
                drawEraserStroke(points, annotation, pageScale, pageOffset)
            }
        }
    }

    private fun DrawScope.drawVariableWidthStroke(
        points: List<PointF>,
        annotation: StrokeAnnotation,
        pageScale: Float,
        pageOffset: Offset,
        alpha: Int
    ) {
        // Same stale-cache fix as the other renderers: color/opacity are editable on a
        // selected ink/signature stroke via the toolbar, so they can't be baked in once.
        // strokeWidth was already being refreshed per-frame below; color/alpha were not.
        val paint = paintCache.getOrPut(annotation.id) {
            AndroidPaint().apply {
                style = AndroidPaint.Style.STROKE
                strokeCap = AndroidPaint.Cap.ROUND
                strokeJoin = AndroidPaint.Join.ROUND
                isAntiAlias = true
            }
        }
        paint.color = annotation.color
        paint.alpha = alpha

        val path = pathCache.getOrPut(annotation.id) { AndroidPath() }
        path.reset()

        val first = points[0]
        path.moveTo(first.x * pageScale + pageOffset.x, first.y * pageScale + pageOffset.y)

        for (i in 1 until points.size) {
            val p = points[i]
            path.lineTo(p.x * pageScale + pageOffset.x, p.y * pageScale + pageOffset.y)
        }

        paint.strokeWidth = annotation.strokeWidth * pageScale
        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawPath(path, paint) }
    }

    private fun DrawScope.drawCalligraphyStroke(
        points: List<PointF>,
        annotation: StrokeAnnotation,
        pageScale: Float,
        pageOffset: Offset,
        alpha: Int
    ) {
        if (points.size < 2) return

        for (i in 1 until points.size) {
            val p1 = points[i - 1]
            val p2 = points[i]

            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val angle = atan2(dy, dx)
            val widthFactor = abs(sin(angle * 2))
            val width = annotation.strokeWidth * (0.5f + 0.5f * widthFactor) * pageScale

            drawLine(
                color = Color(annotation.color),
                start = Offset(p1.x * pageScale + pageOffset.x, p1.y * pageScale + pageOffset.y),
                end = Offset(p2.x * pageScale + pageOffset.x, p2.y * pageScale + pageOffset.y),
                strokeWidth = width,
                alpha = annotation.opacity,
                cap = StrokeCap.Round
            )
        }
    }

    private fun DrawScope.drawMarkerStroke(
        points: List<PointF>,
        annotation: StrokeAnnotation,
        pageScale: Float,
        pageOffset: Offset,
        alpha: Int
    ) {
        val path = androidx.compose.ui.graphics.Path().apply {
            val first = points[0]
            moveTo(first.x * pageScale + pageOffset.x, first.y * pageScale + pageOffset.y)
            points.drop(1).forEach {
                lineTo(it.x * pageScale + pageOffset.x, it.y * pageScale + pageOffset.y)
            }
        }

        drawPath(
            path = path,
            color = Color(annotation.color),
            style = Stroke(
                width = annotation.strokeWidth * 4f * pageScale,
                cap = StrokeCap.Square,
                join = StrokeJoin.Round
            ),
            alpha = annotation.opacity * 0.4f
        )
    }

    private fun DrawScope.drawPencilStroke(
        points: List<PointF>,
        annotation: StrokeAnnotation,
        pageScale: Float,
        pageOffset: Offset,
        alpha: Int
    ) {
        val baseColor = Color(annotation.color)
        val jitter = annotation.strokeWidth * 0.3f * pageScale

        repeat(3) { pass ->
            val path = androidx.compose.ui.graphics.Path().apply {
                val first = points[0]
                val offsetX = (kotlin.random.Random.nextFloat() - 0.5f) * jitter
                val offsetY = (kotlin.random.Random.nextFloat() - 0.5f) * jitter
                moveTo(
                    first.x * pageScale + pageOffset.x + offsetX,
                    first.y * pageScale + pageOffset.y + offsetY
                )
                points.drop(1).forEach { p ->
                    val ox = (kotlin.random.Random.nextFloat() - 0.5f) * jitter
                    val oy = (kotlin.random.Random.nextFloat() - 0.5f) * jitter
                    lineTo(p.x * pageScale + pageOffset.x + ox, p.y * pageScale + pageOffset.y + oy)
                }
            }

            drawPath(
                path = path,
                color = baseColor,
                style = Stroke(
                    width = annotation.strokeWidth * pageScale * (0.5f + pass * 0.2f),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                ),
                alpha = annotation.opacity * (0.4f + pass * 0.2f)
            )
        }
    }

    private fun DrawScope.drawEraserStroke(
        points: List<PointF>,
        annotation: StrokeAnnotation,
        pageScale: Float,
        pageOffset: Offset
    ) {
        val paint = AndroidPaint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            strokeWidth = annotation.strokeWidth * 3f * pageScale
            strokeCap = AndroidPaint.Cap.ROUND
            isAntiAlias = true
        }

        val path = AndroidPath().apply {
            val first = points[0]
            moveTo(first.x * pageScale + pageOffset.x, first.y * pageScale + pageOffset.y)
            points.drop(1).forEach {
                lineTo(it.x * pageScale + pageOffset.x, it.y * pageScale + pageOffset.y)
            }
        }

        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawPath(path, paint)
        }
    }

    private fun DrawScope.renderText(
        annotation: TextAnnotation,
        pageScale: Float,
        pageOffset: Offset,
        alpha: Int
    ) {
        val rect = annotation.rect.toScreen(pageScale, pageOffset)

        annotation.backgroundColor?.let { bgColor ->
            drawRoundRect(
                color = Color(bgColor),
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width(), rect.height()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(annotation.borderRadius * pageScale),
                alpha = annotation.opacity
            )
        }

        if (annotation.borderWidth > 0) {
            annotation.borderColor?.let { borderColor ->
                drawRoundRect(
                    color = Color(borderColor),
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width(), rect.height()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(annotation.borderRadius * pageScale),
                    style = Stroke(annotation.borderWidth * pageScale),
                    alpha = annotation.opacity
                )
            }
        }

        // Same stale-cache issue as the shape/highlight paints above: color, opacity, font
        // size, and bold/italic are all editable on a selected text annotation, so they must
        // be re-applied every render rather than baked in once at first creation.
        val textPaint = paintCache.getOrPut(annotation.id + "_text") {
            AndroidPaint().apply { isAntiAlias = true }
        }
        textPaint.color = annotation.color
        textPaint.alpha = alpha
        textPaint.textSize = annotation.fontSize * pageScale
        textPaint.typeface = when {
            annotation.isBold && annotation.isItalic -> Typeface.defaultFromStyle(Typeface.BOLD_ITALIC)
            annotation.isBold -> Typeface.DEFAULT_BOLD
            annotation.isItalic -> Typeface.defaultFromStyle(Typeface.ITALIC)
            else -> Typeface.DEFAULT
        }

        val lines = annotation.text.split("\n")
        val lineHeight = textPaint.fontMetrics.let { it.descent - it.ascent }
        val totalHeight = lines.size * lineHeight
        val startY = when (annotation.textAlignment) {
            TextAnnotation.TextAlignment.CENTER -> rect.centerY() - totalHeight / 2
            TextAnnotation.TextAlignment.RIGHT -> rect.bottom - totalHeight
            else -> rect.top + annotation.padding * pageScale
        }

        lines.forEachIndexed { index, line ->
            val x = when (annotation.textAlignment) {
                TextAnnotation.TextAlignment.CENTER -> rect.centerX() - textPaint.measureText(line) / 2
                TextAnnotation.TextAlignment.RIGHT -> rect.right - textPaint.measureText(line) - annotation.padding * pageScale
                else -> rect.left + annotation.padding * pageScale
            }
            val y = startY + (index + 1) * lineHeight - textPaint.fontMetrics.descent
            drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(line, x, y, textPaint) }
        }
    }

    private fun DrawScope.renderStamp(
        annotation: StampAnnotation,
        pageScale: Float,
        pageOffset: Offset,
        alpha: Int
    ) {
        val rect = annotation.rect.toScreen(pageScale, pageOffset)

        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.nativeCanvas.rotate(
                annotation.rotation,
                rect.centerX(),
                rect.centerY()
            )

            if (annotation.stampType == StampAnnotation.StampType.CUSTOM_IMAGE && annotation.imagePath != null) {
                // Custom image stamps previously had no rendering path at all (fell through
                // to the text-box branch below with empty text). Draw the decoded bitmap
                // into the annotation's rect, preserving alpha for transparency/opacity.
                val image = annotation.imagePath.let { loadStampImage(it) }
                if (image != null) {
                    val paint = AndroidPaint().apply { this.alpha = alpha; isAntiAlias = true }
                    canvas.nativeCanvas.drawBitmap(
                        image.asAndroidBitmap(),
                        null,
                        rect,
                        paint
                    )
                    canvas.restore()
                    return@drawIntoCanvas
                }
                // Fall through to a placeholder box if the image failed to decode.
            }

            // Predefined stamps (APPROVED/REJECTED/...) keep their semantic default
            // color (getDefaultColor()); CUSTOM_TEXT/DATE_STAMP honor whatever color/
            // background/border the user actually configured in the stamp editor --
            // previously every stamp type ignored annotation.color/backgroundColor/
            // borderColor entirely and always drew with getDefaultColor(), so custom
            // stamp styling had no visible effect.
            val isCustomizable = annotation.stampType == StampAnnotation.StampType.CUSTOM_TEXT ||
                annotation.stampType == StampAnnotation.StampType.DATE_STAMP
            val stampColor = if (isCustomizable) annotation.color else annotation.getDefaultColor()
            val text = annotation.getDisplayText()

            annotation.backgroundColor?.let { bg ->
                val fillPaint = AndroidPaint().apply {
                    color = bg
                    this.alpha = alpha
                    style = AndroidPaint.Style.FILL
                }
                canvas.nativeCanvas.drawRoundRect(
                    android.graphics.RectF(rect), 8f * pageScale, 8f * pageScale, fillPaint
                )
            } ?: run {
                val fillPaint = AndroidPaint().apply {
                    color = stampColor
                    this.alpha = (alpha * 0.15f).toInt()
                    style = AndroidPaint.Style.FILL
                }
                canvas.nativeCanvas.drawRect(rect, fillPaint)
            }

            val borderColor = annotation.borderColor ?: stampColor
            val borderWidth = if (annotation.borderWidth > 0f) annotation.borderWidth * pageScale else 2f * pageScale
            val borderPaint = AndroidPaint().apply {
                color = borderColor
                this.alpha = alpha
                style = AndroidPaint.Style.STROKE
                strokeWidth = borderWidth
            }
            canvas.nativeCanvas.drawRect(rect, borderPaint)

            val textPaint = AndroidPaint().apply {
                color = stampColor
                this.alpha = alpha
                textSize = annotation.fontSize * pageScale
                isFakeBoldText = true
                textAlign = AndroidPaint.Align.CENTER
                isAntiAlias = true
            }

            val fm = textPaint.fontMetrics
            val textY = rect.centerY() - (fm.ascent + fm.descent) / 2
            canvas.nativeCanvas.drawText(text, rect.centerX(), textY, textPaint)

            canvas.restore()
        }
    }

    private fun DrawScope.renderLasso(
        annotation: LassoAnnotation,
        pageScale: Float,
        pageOffset: Offset
    ) {
        if (annotation.polygon.size < 2) return

        val path = androidx.compose.ui.graphics.Path().apply {
            val first = annotation.polygon[0]
            moveTo(first.x * pageScale + pageOffset.x, first.y * pageScale + pageOffset.y)
            annotation.polygon.drop(1).forEach {
                lineTo(it.x * pageScale + pageOffset.x, it.y * pageScale + pageOffset.y)
            }
            close()
        }

        drawPath(
            path = path,
            color = Color(annotation.color),
            style = Fill,
            alpha = annotation.opacity
        )

        drawPath(
            path = path,
            color = Color(annotation.color),
            style = Stroke(2f * pageScale),
            alpha = annotation.opacity * 2
        )
    }

    private fun DrawScope.renderSelectionHandles(
        annotation: Annotation,
        pageScale: Float,
        pageOffset: Offset
    ) {
        val bounds = annotation.getBounds().toScreen(pageScale, pageOffset)
        val handleSize = 8f * pageScale
        val handles = listOf(
            Offset(bounds.left, bounds.top),
            Offset(bounds.centerX(), bounds.top),
            Offset(bounds.right, bounds.top),
            Offset(bounds.right, bounds.centerY()),
            Offset(bounds.right, bounds.bottom),
            Offset(bounds.centerX(), bounds.bottom),
            Offset(bounds.left, bounds.bottom),
            Offset(bounds.left, bounds.centerY())
        )

        handles.forEach { pos ->
            drawCircle(
                color = Color.White,
                radius = handleSize,
                center = pos
            )
            drawCircle(
                color = Color(0xFF2196F3),
                radius = handleSize - 1f,
                center = pos,
                style = Stroke(2f)
            )
        }

        drawRect(
            color = Color(0xFF2196F3),
            topLeft = Offset(bounds.left, bounds.top),
            size = Size(bounds.width(), bounds.height()),
            style = Stroke(1f),
            alpha = 0.5f
        )
    }

    private fun DrawScope.drawSquigglyLine(
        left: Float, right: Float, y: Float,
        color: Int, pageScale: Float, opacity: Float
    ) {
        val path = androidx.compose.ui.graphics.Path()
        val amplitude = 3f * pageScale
        val frequency = 8f * pageScale
        var x = left
        var goingUp = true

        path.moveTo(x, y)
        while (x < right) {
            x = min(x + frequency, right)
            val yOffset = if (goingUp) -amplitude else amplitude
            path.lineTo(x, y + yOffset)
            goingUp = !goingUp
        }

        drawPath(
            path = path,
            color = Color(color),
            style = Stroke((2f * pageScale).coerceAtLeast(2.5f)),
            alpha = opacity
        )
    }

    private fun DrawScope.drawArrowHead(
        start: Offset, end: Offset,
        color: Color, size: Float, alpha: Float
    ) {
        val angle = atan2(end.y - start.y, end.x - start.x)
        val arrowAngle = PI / 6

        val x1 = end.x - size * cos(angle - arrowAngle).toFloat()
        val y1 = end.y - size * sin(angle - arrowAngle).toFloat()
        val x2 = end.x - size * cos(angle + arrowAngle).toFloat()
        val y2 = end.y - size * sin(angle + arrowAngle).toFloat()

        drawLine(color = color, start = end, end = Offset(x1, y1), strokeWidth = size / 3, alpha = alpha)
        drawLine(color = color, start = end, end = Offset(x2, y2), strokeWidth = size / 3, alpha = alpha)
    }

    private fun createCloudPath(
        vertices: List<PointF>,
        pageScale: Float,
        pageOffset: Offset
    ): androidx.compose.ui.graphics.Path {
        val path = androidx.compose.ui.graphics.Path()
        if (vertices.isEmpty()) return path

        val first = vertices[0]
        path.moveTo(first.x * pageScale + pageOffset.x, first.y * pageScale + pageOffset.y)

        for (i in 0 until vertices.size) {
            val current = vertices[i]
            val next = vertices[(i + 1) % vertices.size]
            val midX = (current.x + next.x) / 2f * pageScale + pageOffset.x
            val midY = (current.y + next.y) / 2f * pageScale + pageOffset.y
            val controlX = (current.x * 0.7f + next.x * 0.3f) * pageScale + pageOffset.x
            val controlY = (current.y * 0.7f + next.y * 0.3f) * pageScale + pageOffset.y
            path.quadraticBezierTo(controlX, controlY, midX, midY)
        }

        path.close()
        return path
    }

    private fun RectF.toScreen(scale: Float, offset: Offset): RectF =
        RectF(
            left * scale + offset.x,
            top * scale + offset.y,
            right * scale + offset.x,
            bottom * scale + offset.y
        )

    fun clearCache() {
        paintCache.clear()
        pathCache.clear()
    }
}
