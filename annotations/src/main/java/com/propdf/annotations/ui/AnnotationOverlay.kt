package com.propdf.annotations.ui

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import com.propdf.annotations.model.*
import com.propdf.annotations.model.Annotation
import com.propdf.annotations.smoothing.BezierSmoother
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

/**
 * Annotation overlay composable handling all drawing and touch interactions.
 * Renders on top of PDF viewer with proper coordinate transformation.
 * 
 * Features:
 * - All pen types with pressure simulation
 * - Shape drawing with live preview
 * - Lasso selection with polygon
 * - Text placement on tap
 * - Stamp placement on tap
 * - Multi-touch selection and drag
 */
@Composable
fun AnnotationOverlay(
    viewModel: AnnotationViewModel,
    pageIndex: Int,
    pageWidth: Float,
    pageHeight: Float,
    pageScale: Float,
    pageOffset: Offset,
    modifier: Modifier = Modifier
) {
    val renderer = remember { AnnotationRenderer() }

    val currentTool by viewModel.currentTool.collectAsState()
    val selectedAnnotation by viewModel.selectedAnnotation.collectAsState()
    val selectedAnnotations by viewModel.selectedAnnotations.collectAsState()
    val annotations = remember { derivedStateOf { viewModel.getAnnotationsForPage(pageIndex) } }.value

    // In-progress drawing state
    var currentStroke by remember { mutableStateOf<List<PointF>>(emptyList()) }
    var currentRect by remember { mutableStateOf<android.graphics.RectF?>(null) }
    var currentPolygon by remember { mutableStateOf<List<PointF>>(emptyList()) }
    var lassoPoints by remember { mutableStateOf<List<PointF>>(emptyList()) }
    var isDragging by remember { mutableStateOf(false) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragCurrent by remember { mutableStateOf(Offset.Zero) }
    var isResizing by remember { mutableStateOf(false) }
    var resizeHandle by remember { mutableIntStateOf(-1) }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pageIndex, currentTool) {
                    detectTapGestures(
                        onTap = { offset ->
                            val pdfPoint = screenToPdf(offset, pageScale, pageOffset)
                            handleTap(viewModel, currentTool, pdfPoint, pageIndex)
                        },
                        onDoubleTap = { offset ->
                            val pdfPoint = screenToPdf(offset, pageScale, pageOffset)
                            val allAnnotations = viewModel.getAnnotationsForPage(pageIndex)
                            val hit = allAnnotations.find { it.hitTest(pdfPoint.x, pdfPoint.y) }
                            if (hit != null) {
                                viewModel.selectAnnotation(hit, false)
                            }
                        },
                        onLongPress = { offset ->
                            val pdfPoint = screenToPdf(offset, pageScale, pageOffset)
                            if (currentTool == AnnotationViewModel.AnnotationTool.SELECTOR) {
                                val allAnnotations = viewModel.getAnnotationsForPage(pageIndex)
                                val hit = allAnnotations.find { it.hitTest(pdfPoint.x, pdfPoint.y) }
                                if (hit != null) {
                                    viewModel.selectAnnotation(hit, true) // Additive selection
                                }
                            }
                        }
                    )
                }
                .pointerInput(pageIndex, currentTool) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val pdfPoint = screenToPdf(offset, pageScale, pageOffset)
                            dragStart = offset
                            dragCurrent = offset

                            when (currentTool) {
                                AnnotationViewModel.AnnotationTool.PEN,
                                AnnotationViewModel.AnnotationTool.CALLIGRAPHY,
                                AnnotationViewModel.AnnotationTool.MARKER,
                                AnnotationViewModel.AnnotationTool.PENCIL,
                                AnnotationViewModel.AnnotationTool.ERASER,
                                AnnotationViewModel.AnnotationTool.SIGNATURE -> {
                                    currentStroke = listOf(
                                        PointF(pdfPoint.x, pdfPoint.y, 
                                            if (currentTool == AnnotationViewModel.AnnotationTool.PENCIL) 0.7f else 1.0f)
                                    )
                                }
                                AnnotationViewModel.AnnotationTool.RECTANGLE,
                                AnnotationViewModel.AnnotationTool.CIRCLE,
                                AnnotationViewModel.AnnotationTool.LINE,
                                AnnotationViewModel.AnnotationTool.ARROW -> {
                                    currentRect = android.graphics.RectF(pdfPoint.x, pdfPoint.y, pdfPoint.x, pdfPoint.y)
                                }
                                AnnotationViewModel.AnnotationTool.POLYGON,
                                AnnotationViewModel.AnnotationTool.CLOUD -> {
                                    if (currentPolygon.isEmpty()) {
                                        currentPolygon = listOf(PointF(pdfPoint.x, pdfPoint.y))
                                    }
                                }
                                AnnotationViewModel.AnnotationTool.LASSO -> {
                                    lassoPoints = listOf(PointF(pdfPoint.x, pdfPoint.y))
                                }
                                AnnotationViewModel.AnnotationTool.SELECTOR -> {
                                    val allAnnotations = viewModel.getAnnotationsForPage(pageIndex)
                                    val hit = allAnnotations.find { it.hitTest(pdfPoint.x, pdfPoint.y) }
                                    if (hit != null) {
                                        viewModel.selectAnnotation(hit, false)
                                        isDragging = true
                                    } else {
                                        // Check if clicking on resize handle
                                        if (selectedAnnotations.isNotEmpty()) {
                                            val selectionTool = com.propdf.annotations.transform.SelectionTool()
                                            selectedAnnotations.forEach { selectionTool.select(it) }
                                            val handle = selectionTool.getHandleAt(pdfPoint.x, pdfPoint.y, 15f / pageScale)
                                            if (handle != -1) {
                                                isResizing = true
                                                resizeHandle = handle
                                            }
                                        }
                                    }
                                }
                                else -> {}
                            }
                        },
                        onDrag = { change, dragAmount ->
                            val currentOffset = change.position
                            val pdfPoint = screenToPdf(currentOffset, pageScale, pageOffset)

                            when (currentTool) {
                                AnnotationViewModel.AnnotationTool.PEN,
                                AnnotationViewModel.AnnotationTool.CALLIGRAPHY,
                                AnnotationViewModel.AnnotationTool.MARKER,
                                AnnotationViewModel.AnnotationTool.PENCIL,
                                AnnotationViewModel.AnnotationTool.ERASER,
                                AnnotationViewModel.AnnotationTool.SIGNATURE -> {
                                    val pressure = when (currentTool) {
                                        AnnotationViewModel.AnnotationTool.PENCIL -> {
                                            0.5f + kotlin.random.Random.nextFloat() * 0.3f
                                        }
                                        AnnotationViewModel.AnnotationTool.MARKER -> 0.8f
                                        else -> 1.0f
                                    }
                                    currentStroke = currentStroke + PointF(pdfPoint.x, pdfPoint.y, pressure)
                                }
                                AnnotationViewModel.AnnotationTool.RECTANGLE,
                                AnnotationViewModel.AnnotationTool.CIRCLE,
                                AnnotationViewModel.AnnotationTool.LINE,
                                AnnotationViewModel.AnnotationTool.ARROW -> {
                                    currentRect?.let { rect ->
                                        currentRect = android.graphics.RectF(
                                            rect.left, rect.top,
                                            pdfPoint.x, pdfPoint.y
                                        )
                                    }
                                }
                                AnnotationViewModel.AnnotationTool.POLYGON,
                                AnnotationViewModel.AnnotationTool.CLOUD -> {
                                    // Polygon points added on tap, not drag
                                }
                                AnnotationViewModel.AnnotationTool.LASSO -> {
                                    lassoPoints = lassoPoints + PointF(pdfPoint.x, pdfPoint.y)
                                }
                                AnnotationViewModel.AnnotationTool.SELECTOR -> {
                                    if (isDragging && selectedAnnotations.isNotEmpty()) {
                                        val dx = dragAmount.x / pageScale
                                        val dy = dragAmount.y / pageScale
                                        viewModel.moveSelected(dx, dy)
                                    } else if (isResizing && selectedAnnotations.isNotEmpty()) {
                                        // Handle resize logic based on handle index
                                        val dx = dragAmount.x / pageScale
                                        val dy = dragAmount.y / pageScale
                                        // Apply resize to selected annotations
                                        selectedAnnotations.forEach { annotation ->
                                            val bounds = annotation.getBounds()
                                            val newRect = when (resizeHandle) {
                                                0 -> android.graphics.RectF(bounds.left + dx, bounds.top + dy, bounds.right, bounds.bottom) // Top-left
                                                1 -> android.graphics.RectF(bounds.left, bounds.top + dy, bounds.right, bounds.bottom) // Top-center
                                                2 -> android.graphics.RectF(bounds.left, bounds.top + dy, bounds.right + dx, bounds.bottom) // Top-right
                                                3 -> android.graphics.RectF(bounds.left, bounds.top, bounds.right + dx, bounds.bottom) // Right-center
                                                4 -> android.graphics.RectF(bounds.left, bounds.top, bounds.right + dx, bounds.bottom + dy) // Bottom-right
                                                5 -> android.graphics.RectF(bounds.left, bounds.top, bounds.right, bounds.bottom + dy) // Bottom-center
                                                6 -> android.graphics.RectF(bounds.left + dx, bounds.top, bounds.right, bounds.bottom + dy) // Bottom-left
                                                7 -> android.graphics.RectF(bounds.left + dx, bounds.top, bounds.right, bounds.bottom) // Left-center
                                                else -> bounds
                                            }
                                            // Update annotation bounds (simplified - actual implementation would vary by type)
                                        }
                                    }
                                }
                                else -> {}
                            }
                            dragCurrent = currentOffset
                        },
                        onDragEnd = {
                            when (currentTool) {
                                AnnotationViewModel.AnnotationTool.PEN,
                                AnnotationViewModel.AnnotationTool.CALLIGRAPHY,
                                AnnotationViewModel.AnnotationTool.MARKER,
                                AnnotationViewModel.AnnotationTool.PENCIL,
                                AnnotationViewModel.AnnotationTool.ERASER,
                                AnnotationViewModel.AnnotationTool.SIGNATURE -> {
                                    if (currentStroke.size >= 2) {
                                        val smoothed = when (currentTool) {
                                            AnnotationViewModel.AnnotationTool.PENCIL -> currentStroke
                                            else -> BezierSmoother.smooth(currentStroke)
                                        }
                                        viewModel.createStrokeAnnotation(pageIndex, smoothed)
                                    }
                                    currentStroke = emptyList()
                                }
                                AnnotationViewModel.AnnotationTool.RECTANGLE -> {
                                    currentRect?.let { rect ->
                                        if (abs(rect.width()) > 5 && abs(rect.height()) > 5) {
                                            viewModel.createShapeAnnotation(
                                                pageIndex,
                                                ShapeAnnotation.ShapeType.RECTANGLE,
                                                normalizeRect(rect)
                                            )
                                        }
                                    }
                                    currentRect = null
                                }
                                AnnotationViewModel.AnnotationTool.CIRCLE -> {
                                    currentRect?.let { rect ->
                                        if (abs(rect.width()) > 5 && abs(rect.height()) > 5) {
                                            viewModel.createShapeAnnotation(
                                                pageIndex,
                                                ShapeAnnotation.ShapeType.CIRCLE,
                                                normalizeRect(rect)
                                            )
                                        }
                                    }
                                    currentRect = null
                                }
                                AnnotationViewModel.AnnotationTool.LINE -> {
                                    currentRect?.let { rect ->
                                        viewModel.createShapeAnnotation(
                                            pageIndex,
                                            ShapeAnnotation.ShapeType.LINE,
                                            normalizeRect(rect)
                                        )
                                    }
                                    currentRect = null
                                }
                                AnnotationViewModel.AnnotationTool.ARROW -> {
                                    currentRect?.let { rect ->
                                        viewModel.createShapeAnnotation(
                                            pageIndex,
                                            ShapeAnnotation.ShapeType.ARROW,
                                            normalizeRect(rect)
                                        )
                                    }
                                    currentRect = null
                                }
                                AnnotationViewModel.AnnotationTool.LASSO -> {
                                    if (lassoPoints.size >= 3) {
                                        val lasso = LassoAnnotation(
                                            pageIndex = pageIndex,
                                            polygon = lassoPoints
                                        )
                                        viewModel.selectByLasso(lasso)
                                    }
                                    lassoPoints = emptyList()
                                }
                                AnnotationViewModel.AnnotationTool.SELECTOR -> {
                                    isDragging = false
                                    isResizing = false
                                    resizeHandle = -1
                                }
                                else -> {}
                            }
                        }
                    )
                }
        ) {
            // Render existing annotations
            annotations.forEach { annotation ->
                renderer.renderAnnotation(annotation, pageScale, pageOffset)
            }

            // Render in-progress strokes
            if (currentStroke.size >= 2) {
                val penType = when (currentTool) {
                    AnnotationViewModel.AnnotationTool.CALLIGRAPHY -> StrokeAnnotation.PenType.CALLIGRAPHY
                    AnnotationViewModel.AnnotationTool.MARKER -> StrokeAnnotation.PenType.MARKER
                    AnnotationViewModel.AnnotationTool.PENCIL -> StrokeAnnotation.PenType.PENCIL
                    AnnotationViewModel.AnnotationTool.ERASER -> StrokeAnnotation.PenType.ERASER
                    AnnotationViewModel.AnnotationTool.SIGNATURE -> StrokeAnnotation.PenType.SIGNATURE
                    else -> StrokeAnnotation.PenType.INK
                }
                val tempAnnotation = StrokeAnnotation(
                    pageIndex = pageIndex,
                    points = currentStroke,
                    strokeWidth = 3f,
                    penType = penType,
                    color = android.graphics.Color.BLACK
                )
                renderer.renderAnnotation(tempAnnotation, pageScale, pageOffset)
            }

            // Render in-progress rect
            currentRect?.let { rect ->
                val shapeType = when (currentTool) {
                    AnnotationViewModel.AnnotationTool.RECTANGLE -> ShapeAnnotation.ShapeType.RECTANGLE
                    AnnotationViewModel.AnnotationTool.CIRCLE -> ShapeAnnotation.ShapeType.CIRCLE
                    AnnotationViewModel.AnnotationTool.LINE -> ShapeAnnotation.ShapeType.LINE
                    AnnotationViewModel.AnnotationTool.ARROW -> ShapeAnnotation.ShapeType.ARROW
                    else -> ShapeAnnotation.ShapeType.RECTANGLE
                }
                val tempAnnotation = ShapeAnnotation(
                    pageIndex = pageIndex,
                    shapeType = shapeType,
                    rect = rect,
                    color = android.graphics.Color.BLACK
                )
                renderer.renderAnnotation(tempAnnotation, pageScale, pageOffset)
            }

            // Render in-progress polygon
            if (currentPolygon.size >= 2) {
                val tempAnnotation = ShapeAnnotation(
                    pageIndex = pageIndex,
                    shapeType = if (currentTool == AnnotationViewModel.AnnotationTool.CLOUD) 
                        ShapeAnnotation.ShapeType.CLOUD else ShapeAnnotation.ShapeType.POLYGON,
                    rect = android.graphics.RectF(),
                    vertices = currentPolygon,
                    color = android.graphics.Color.BLACK
                )
                renderer.renderAnnotation(tempAnnotation, pageScale, pageOffset)
            }

            // Render in-progress lasso
            if (lassoPoints.size >= 2) {
                val tempLasso = LassoAnnotation(
                    pageIndex = pageIndex,
                    polygon = lassoPoints
                )
                renderer.renderAnnotation(tempLasso, pageScale, pageOffset)
            }
        }
    }
}

private fun screenToPdf(screenPoint: Offset, pageScale: Float, pageOffset: Offset): Offset {
    return Offset(
        (screenPoint.x - pageOffset.x) / pageScale,
        (screenPoint.y - pageOffset.y) / pageScale
    )
}

private fun normalizeRect(rect: android.graphics.RectF): android.graphics.RectF {
    return android.graphics.RectF(
        kotlin.math.min(rect.left, rect.right),
        kotlin.math.min(rect.top, rect.bottom),
        kotlin.math.max(rect.left, rect.right),
        kotlin.math.max(rect.top, rect.bottom)
    )
}

private fun handleTap(
    viewModel: AnnotationViewModel,
    tool: AnnotationViewModel.AnnotationTool,
    point: Offset,
    pageIndex: Int
) {
    when (tool) {
        AnnotationViewModel.AnnotationTool.TEXT,
        AnnotationViewModel.AnnotationTool.FREE_TEXT -> {
            viewModel.createTextAnnotation(
                pageIndex,
                TextAnnotation.TextType.FREE_TEXT,
                android.graphics.RectF(point.x - 50, point.y - 10, point.x + 50, point.y + 10),
                "Text"
            )
        }
        AnnotationViewModel.AnnotationTool.STICKY_NOTE -> {
            viewModel.createTextAnnotation(
                pageIndex,
                TextAnnotation.TextType.STICKY_NOTE,
                android.graphics.RectF(point.x - 15, point.y - 15, point.x + 15, point.y + 15),
                ""
            )
        }
        AnnotationViewModel.AnnotationTool.TEXTBOX -> {
            viewModel.createTextAnnotation(
                pageIndex,
                TextAnnotation.TextType.TEXTBOX,
                android.graphics.RectF(point.x - 50, point.y - 20, point.x + 50, point.y + 20),
                "Text Box"
            )
        }
        AnnotationViewModel.AnnotationTool.STAMP -> {
            viewModel.createStampAnnotation(
                pageIndex,
                StampAnnotation.StampType.APPROVED,
                android.graphics.RectF(point.x - 40, point.y - 15, point.x + 40, point.y + 15)
            )
        }
        AnnotationViewModel.AnnotationTool.DATE_STAMP -> {
            viewModel.createStampAnnotation(
                pageIndex,
                StampAnnotation.StampType.DATE_STAMP,
                android.graphics.RectF(point.x - 40, point.y - 15, point.x + 40, point.y + 15)
            )
        }
        AnnotationViewModel.AnnotationTool.POLYGON,
        AnnotationViewModel.AnnotationTool.CLOUD -> {
            // Add point to polygon on tap
            // In a real implementation, you'd track polygon building state
        }
        AnnotationViewModel.AnnotationTool.SELECTOR -> {
            val allAnnotations = viewModel.getAnnotationsForPage(pageIndex)
            val hit = allAnnotations.find { it.hitTest(point.x, point.y) }
            if (hit != null) {
                viewModel.selectAnnotation(hit, false)
            } else {
                viewModel.deselectAll()
            }
        }
        else -> {}
    }
}
