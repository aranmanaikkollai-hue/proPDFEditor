package com.propdf.annotations.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.propdf.annotations.model.*
import com.propdf.annotations.layers.LayerManager
import kotlinx.coroutines.flow.StateFlow

/**
 * Compose Canvas overlay for rendering annotations on PDF pages.
 * Handles touch input for drawing, selecting, and editing annotations.
 */
@Composable
fun AnnotationOverlay(
    pageIndex: Int,
    layerManager: LayerManager,
    currentTool: AnnotationTool,
    currentColor: Color,
    currentStrokeWidth: Float,
    onAnnotationCreated: (Annotation) -> Unit,
    onAnnotationSelected: (Annotation?) -> Unit,
    modifier: Modifier = Modifier
) {
    val annotations by remember { derivedStateOf {
        layerManager.getAnnotationsForPage(pageIndex)
    }}

    var currentStroke by remember { mutableStateOf<List<PointF>>(emptyList()) }
    var isDrawing by remember { mutableStateOf(false) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(currentTool) {
                when (currentTool) {
                    AnnotationTool.PEN, AnnotationTool.HIGHLIGHTER -> {
                        detectDragGestures(
                            onDragStart = { offset ->
                                isDrawing = true
                                currentStroke = listOf(PointF(offset.x, offset.y))
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                currentStroke = currentStroke + PointF(change.position.x, change.position.y)
                            },
                            onDragEnd = {
                                isDrawing = false
                                val annotation = createStrokeAnnotation(
                                    pageIndex = pageIndex,
                                    points = currentStroke,
                                    color = currentColor,
                                    strokeWidth = currentStrokeWidth,
                                    isHighlighter = currentTool == AnnotationTool.HIGHLIGHTER
                                )
                                onAnnotationCreated(annotation)
                                currentStroke = emptyList()
                            }
                        )
                    }
                    AnnotationTool.SELECTOR -> {
                        detectTapGestures { offset ->
                            val tapped = annotations.find { it.hitTest(offset.x, offset.y) }
                            onAnnotationSelected(tapped)
                        }
                    }
                    else -> { /* Other tools handled separately */ }
                }
            }
    ) {
        // Draw existing annotations
        annotations.forEach { annotation ->
            drawAnnotation(annotation)
        }

        // Draw current stroke being created
        if (isDrawing && currentStroke.size > 1) {
            drawStroke(
                points = currentStroke,
                color = currentColor,
                strokeWidth = currentStrokeWidth,
                opacity = if (currentTool == AnnotationTool.HIGHLIGHTER) 0.3f else 1.0f
            )
        }
    }
}

private fun DrawScope.drawAnnotation(annotation: Annotation) {
    when (annotation) {
        is StrokeAnnotation -> drawStroke(
            points = annotation.getRenderPoints(),
            color = Color(annotation.color),
            strokeWidth = annotation.strokeWidth,
            opacity = annotation.opacity
        )
        is ShapeAnnotation -> drawShape(annotation)
        is TextAnnotation -> drawTextAnnotation(annotation)
        is HighlightAnnotation -> drawHighlight(annotation)
        else -> {}
    }
}

private fun DrawScope.drawStroke(
    points: List<PointF>,
    color: Color,
    strokeWidth: Float,
    opacity: Float
) {
    if (points.size < 2) return

    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            lineTo(points[i].x, points[i].y)
        }
    }

    drawPath(
        path = path,
        color = color.copy(alpha = opacity),
        style = Stroke(
            width = strokeWidth.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

private fun DrawScope.drawShape(annotation: ShapeAnnotation) {
    val color = Color(annotation.color).copy(alpha = annotation.opacity)
    val style = Stroke(width = annotation.strokeWidth.dp.toPx())

    when (annotation.shapeType) {
        ShapeAnnotation.ShapeType.RECTANGLE -> {
            drawRect(
                color = color,
                topLeft = Offset(annotation.rect.left, annotation.rect.top),
                size = androidx.compose.ui.geometry.Size(
                    annotation.rect.width(),
                    annotation.rect.height()
                ),
                style = style
            )
        }
        ShapeAnnotation.ShapeType.CIRCLE -> {
            drawCircle(
                color = color,
                center = Offset(annotation.rect.centerX(), annotation.rect.centerY()),
                radius = annotation.rect.width() / 2,
                style = style
            )
        }
        ShapeAnnotation.ShapeType.LINE -> {
            drawLine(
                color = color,
                start = Offset(annotation.rect.left, annotation.rect.top),
                end = Offset(annotation.rect.right, annotation.rect.bottom),
                strokeWidth = annotation.strokeWidth.dp.toPx()
            )
        }
        else -> {}
    }

    annotation.fillColor?.let { fill ->
        // Fill shape
    }
}

private fun DrawScope.drawTextAnnotation(annotation: TextAnnotation) {
    // Text rendering requires native canvas or Text composible overlay
    // Simplified: draw bounding box
    drawRect(
        color = Color(annotation.color).copy(alpha = 0.1f),
        topLeft = Offset(annotation.rect.left, annotation.rect.top),
        size = androidx.compose.ui.geometry.Size(
            annotation.rect.width(),
            annotation.rect.height()
        )
    )
}

private fun DrawScope.drawHighlight(annotation: HighlightAnnotation) {
    val color = Color(annotation.color).copy(alpha = annotation.opacity)
    annotation.rects.forEach { rect ->
        drawRect(
            color = color,
            topLeft = Offset(rect.left, rect.top),
            size = androidx.compose.ui.geometry.Size(rect.width(), rect.height())
        )
    }
}

private fun createStrokeAnnotation(
    pageIndex: Int,
    points: List<PointF>,
    color: Color,
    strokeWidth: Float,
    isHighlighter: Boolean
): StrokeAnnotation {
    return StrokeAnnotation(
        pageIndex = pageIndex,
        points = points,
        strokeWidth = strokeWidth,
        color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        ),
        opacity = if (isHighlighter) 0.3f else 1.0f,
        isSmooth = true
    )
}

enum class AnnotationTool {
    PEN,
    HIGHLIGHTER,
    ERASER,
    SELECTOR,
    TEXT,
    SHAPE_RECTANGLE,
    SHAPE_CIRCLE,
    SHAPE_ARROW
}
