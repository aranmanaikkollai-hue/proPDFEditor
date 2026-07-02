package com.propdf.annotations.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.propdf.annotations.model.*
import com.propdf.annotations.model.Annotation
import com.propdf.annotations.smoothing.BezierSmoother
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Annotation overlay composable handling all drawing and touch interactions.
 * Renders on top of PDF viewer with proper coordinate transformation.
 *
 * Features:
 * - All pen types with pressure simulation
 * - A real eraser (splits/removes underlying ink strokes, not a fake "clear" paint)
 * - Markup tools (highlight/underline/strikeout/squiggly) via drag
 * - Shape drawing with live preview, including polygon/cloud via tap-to-add-vertex
 * - Lasso selection with polygon
 * - Text placement with an actual text-entry dialog (tap an existing text annotation
 *   with the Select tool to edit it)
 * - Multi-touch selection, live move, and live resize
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
    val currentStrokeWidth by viewModel.currentStrokeWidth.collectAsState()
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
    var pendingTextEdit by remember { mutableStateOf<PendingTextEdit?>(null) }

    // Reset any tool-specific in-progress state when the tool changes, so switching
    // tools mid-gesture (e.g. tapping a different tool while building a polygon)
    // doesn't leave stale points lying around.
    LaunchedEffect(currentTool) {
        currentPolygon = emptyList()
        currentStroke = emptyList()
        currentRect = null
        lassoPoints = emptyList()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pageIndex, currentTool) {
                    detectTapGestures(
                        onTap = { offset ->
                            val pdfPoint = screenToPdf(offset, pageScale, pageOffset)
                            when (currentTool) {
                                AnnotationViewModel.AnnotationTool.POLYGON,
                                AnnotationViewModel.AnnotationTool.CLOUD -> {
                                    val newPoint = PointF(pdfPoint.x, pdfPoint.y)
                                    val closeThreshold = 24f / pageScale
                                    val first = currentPolygon.firstOrNull()
                                    if (first != null && currentPolygon.size >= 3 &&
                                        hypot(newPoint.x - first.x, newPoint.y - first.y) <= closeThreshold
                                    ) {
                                        finishPolygon(viewModel, pageIndex, currentTool, currentPolygon)
                                        currentPolygon = emptyList()
                                    } else {
                                        currentPolygon = currentPolygon + newPoint
                                    }
                                }
                                else -> handleTap(viewModel, currentTool, pdfPoint, pageIndex) { pending ->
                                    pendingTextEdit = pending
                                }
                            }
                        },
                        onDoubleTap = { offset ->
                            val pdfPoint = screenToPdf(offset, pageScale, pageOffset)
                            when (currentTool) {
                                AnnotationViewModel.AnnotationTool.POLYGON,
                                AnnotationViewModel.AnnotationTool.CLOUD -> {
                                    finishPolygon(viewModel, pageIndex, currentTool, currentPolygon)
                                    currentPolygon = emptyList()
                                }
                                AnnotationViewModel.AnnotationTool.SELECTOR -> {
                                    val allAnnotations = viewModel.getAnnotationsForPage(pageIndex)
                                    val hit = allAnnotations.find { it.hitTest(pdfPoint.x, pdfPoint.y) }
                                    if (hit is TextAnnotation) {
                                        pendingTextEdit = PendingTextEdit(
                                            pageIndex = pageIndex,
                                            rect = hit.rect,
                                            textType = hit.textType,
                                            initialText = hit.text,
                                            existingAnnotationId = hit.id
                                        )
                                    } else if (hit != null) {
                                        viewModel.selectAnnotation(hit, false)
                                    }
                                }
                                else -> {}
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
                                AnnotationViewModel.AnnotationTool.SIGNATURE -> {
                                    currentStroke = listOf(
                                        PointF(
                                            pdfPoint.x, pdfPoint.y,
                                            if (currentTool == AnnotationViewModel.AnnotationTool.PENCIL) 0.7f else 1.0f
                                        )
                                    )
                                }
                                AnnotationViewModel.AnnotationTool.ERASER -> {
                                    currentStroke = listOf(PointF(pdfPoint.x, pdfPoint.y))
                                }
                                AnnotationViewModel.AnnotationTool.RECTANGLE,
                                AnnotationViewModel.AnnotationTool.CIRCLE,
                                AnnotationViewModel.AnnotationTool.LINE,
                                AnnotationViewModel.AnnotationTool.ARROW,
                                AnnotationViewModel.AnnotationTool.HIGHLIGHT,
                                AnnotationViewModel.AnnotationTool.UNDERLINE,
                                AnnotationViewModel.AnnotationTool.STRIKEOUT,
                                AnnotationViewModel.AnnotationTool.SQUIGGLY -> {
                                    currentRect = android.graphics.RectF(pdfPoint.x, pdfPoint.y, pdfPoint.x, pdfPoint.y)
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
                                    } else if (selectedAnnotations.isNotEmpty()) {
                                        val handle = viewModel.getSelectionHandleAt(pdfPoint.x, pdfPoint.y, 20f / pageScale)
                                        if (handle != -1) {
                                            isResizing = true
                                            resizeHandle = handle
                                        }
                                    }
                                }
                                else -> {} // POLYGON/CLOUD are built via tap, not drag
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
                                AnnotationViewModel.AnnotationTool.ERASER -> {
                                    currentStroke = currentStroke + PointF(pdfPoint.x, pdfPoint.y)
                                }
                                AnnotationViewModel.AnnotationTool.RECTANGLE,
                                AnnotationViewModel.AnnotationTool.CIRCLE,
                                AnnotationViewModel.AnnotationTool.LINE,
                                AnnotationViewModel.AnnotationTool.ARROW,
                                AnnotationViewModel.AnnotationTool.HIGHLIGHT,
                                AnnotationViewModel.AnnotationTool.UNDERLINE,
                                AnnotationViewModel.AnnotationTool.STRIKEOUT,
                                AnnotationViewModel.AnnotationTool.SQUIGGLY -> {
                                    currentRect?.let { rect ->
                                        currentRect = android.graphics.RectF(
                                            rect.left, rect.top,
                                            pdfPoint.x, pdfPoint.y
                                        )
                                    }
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
                                        val bounds = viewModel.getSelectionBounds()
                                        if (bounds != null && resizeHandle in 0..7) {
                                            val handles = selectionHandlePositions(bounds)
                                            val handlePos = handles[resizeHandle]
                                            val pivot = handles[(resizeHandle + 4) % 8]
                                            val oldDist = hypot(handlePos.x - pivot.x, handlePos.y - pivot.y)
                                            val dx = dragAmount.x / pageScale
                                            val dy = dragAmount.y / pageScale
                                            val newDist = hypot(
                                                (handlePos.x + dx) - pivot.x,
                                                (handlePos.y + dy) - pivot.y
                                            )
                                            if (oldDist > 1f) {
                                                val factor = (newDist / oldDist).coerceIn(0.5f, 2f)
                                                viewModel.scaleSelected(factor, pivot.x, pivot.y)
                                            }
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
                                AnnotationViewModel.AnnotationTool.ERASER -> {
                                    if (currentStroke.size >= 1) {
                                        val eraserRadius = (currentStrokeWidth * 4f).coerceAtLeast(6f)
                                        viewModel.eraseAtPath(pageIndex, currentStroke, eraserRadius)
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
                                AnnotationViewModel.AnnotationTool.HIGHLIGHT,
                                AnnotationViewModel.AnnotationTool.UNDERLINE,
                                AnnotationViewModel.AnnotationTool.STRIKEOUT,
                                AnnotationViewModel.AnnotationTool.SQUIGGLY -> {
                                    currentRect?.let { rect ->
                                        if (abs(rect.width()) > 5 && abs(rect.height()) > 5) {
                                            val highlightType = when (currentTool) {
                                                AnnotationViewModel.AnnotationTool.UNDERLINE -> HighlightAnnotation.HighlightType.UNDERLINE
                                                AnnotationViewModel.AnnotationTool.STRIKEOUT -> HighlightAnnotation.HighlightType.STRIKEOUT
                                                AnnotationViewModel.AnnotationTool.SQUIGGLY -> HighlightAnnotation.HighlightType.SQUIGGLY
                                                else -> HighlightAnnotation.HighlightType.HIGHLIGHT
                                            }
                                            viewModel.createHighlightAnnotation(
                                                pageIndex,
                                                highlightType,
                                                listOf(normalizeRect(rect))
                                            )
                                        }
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
            with(renderer) {
                // Render existing annotations
                annotations.forEach { annotation ->
                    renderAnnotation(annotation, pageScale, pageOffset)
                }

                // Render in-progress ink strokes (not shown for the eraser — see below)
                if (currentStroke.size >= 2 && currentTool != AnnotationViewModel.AnnotationTool.ERASER) {
                    val penType = when (currentTool) {
                        AnnotationViewModel.AnnotationTool.CALLIGRAPHY -> StrokeAnnotation.PenType.CALLIGRAPHY
                        AnnotationViewModel.AnnotationTool.MARKER -> StrokeAnnotation.PenType.MARKER
                        AnnotationViewModel.AnnotationTool.PENCIL -> StrokeAnnotation.PenType.PENCIL
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
                    renderAnnotation(tempAnnotation, pageScale, pageOffset)
                }

                // Render in-progress rect (shapes and markup share this preview)
                currentRect?.let { rect ->
                    when (currentTool) {
                        AnnotationViewModel.AnnotationTool.HIGHLIGHT,
                        AnnotationViewModel.AnnotationTool.UNDERLINE,
                        AnnotationViewModel.AnnotationTool.STRIKEOUT,
                        AnnotationViewModel.AnnotationTool.SQUIGGLY -> {
                            val highlightType = when (currentTool) {
                                AnnotationViewModel.AnnotationTool.UNDERLINE -> HighlightAnnotation.HighlightType.UNDERLINE
                                AnnotationViewModel.AnnotationTool.STRIKEOUT -> HighlightAnnotation.HighlightType.STRIKEOUT
                                AnnotationViewModel.AnnotationTool.SQUIGGLY -> HighlightAnnotation.HighlightType.SQUIGGLY
                                else -> HighlightAnnotation.HighlightType.HIGHLIGHT
                            }
                            val tempHighlight = HighlightAnnotation(
                                pageIndex = pageIndex,
                                highlightType = highlightType,
                                rects = listOf(normalizeRect(rect)),
                                color = android.graphics.Color.YELLOW,
                                opacity = 0.3f
                            )
                            renderAnnotation(tempHighlight, pageScale, pageOffset)
                        }
                        else -> {
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
                            renderAnnotation(tempAnnotation, pageScale, pageOffset)
                        }
                    }
                }

                // Render in-progress polygon/cloud (tap-built)
                if (currentPolygon.size >= 2) {
                    val tempAnnotation = ShapeAnnotation(
                        pageIndex = pageIndex,
                        shapeType = if (currentTool == AnnotationViewModel.AnnotationTool.CLOUD)
                            ShapeAnnotation.ShapeType.CLOUD else ShapeAnnotation.ShapeType.POLYGON,
                        rect = android.graphics.RectF(),
                        vertices = currentPolygon,
                        color = android.graphics.Color.BLACK
                    )
                    renderAnnotation(tempAnnotation, pageScale, pageOffset)
                }

                // Render in-progress lasso
                if (lassoPoints.size >= 2) {
                    val tempLasso = LassoAnnotation(
                        pageIndex = pageIndex,
                        polygon = lassoPoints
                    )
                    renderAnnotation(tempLasso, pageScale, pageOffset)
                }
            }

            // Eraser cursor trail — a plain translucent circle along the drag path, not tied
            // to the annotation rendering system, since erasing now deletes/splits data
            // directly rather than "painting" a clear stroke.
            if (currentTool == AnnotationViewModel.AnnotationTool.ERASER && currentStroke.isNotEmpty()) {
                val eraserRadiusPx = ((currentStrokeWidth * 4f).coerceAtLeast(6f)) * pageScale
                currentStroke.forEach { point ->
                    val screenX = point.x * pageScale + pageOffset.x
                    val screenY = point.y * pageScale + pageOffset.y
                    drawCircle(
                        color = Color.Gray,
                        radius = eraserRadiusPx,
                        center = Offset(screenX, screenY),
                        alpha = 0.35f
                    )
                }
            }
        }
    }

    pendingTextEdit?.let { pending ->
        TextAnnotationEditDialog(
            title = when (pending.textType) {
                TextAnnotation.TextType.STICKY_NOTE -> "Sticky note"
                TextAnnotation.TextType.TEXTBOX -> "Text box"
                TextAnnotation.TextType.FREE_TEXT -> "Text"
            },
            initialText = pending.initialText,
            onConfirm = { text ->
                if (pending.existingAnnotationId != null) {
                    viewModel.updateTextAnnotationContent(pending.existingAnnotationId, text)
                } else if (text.isNotBlank()) {
                    viewModel.createTextAnnotation(pending.pageIndex, pending.textType, pending.rect, text)
                }
                pendingTextEdit = null
            },
            onDismiss = { pendingTextEdit = null }
        )
    }
}

/** Describes a text annotation about to be placed (existingAnnotationId == null) or edited. */
private data class PendingTextEdit(
    val pageIndex: Int,
    val rect: android.graphics.RectF,
    val textType: TextAnnotation.TextType,
    val initialText: String,
    val existingAnnotationId: String?
)

@Composable
private fun TextAnnotationEditDialog(
    title: String,
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                minLines = 2,
                maxLines = 6
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** The 8 handle positions (corners + edge midpoints) for a bounding rect, matching SelectionTool. */
private fun selectionHandlePositions(bounds: android.graphics.RectF): List<Offset> = listOf(
    Offset(bounds.left, bounds.top),
    Offset(bounds.centerX(), bounds.top),
    Offset(bounds.right, bounds.top),
    Offset(bounds.right, bounds.centerY()),
    Offset(bounds.right, bounds.bottom),
    Offset(bounds.centerX(), bounds.bottom),
    Offset(bounds.left, bounds.bottom),
    Offset(bounds.left, bounds.centerY())
)

private fun finishPolygon(
    viewModel: AnnotationViewModel,
    pageIndex: Int,
    tool: AnnotationViewModel.AnnotationTool,
    vertices: List<PointF>
) {
    if (vertices.size < 3) return
    val shapeType = if (tool == AnnotationViewModel.AnnotationTool.CLOUD)
        ShapeAnnotation.ShapeType.CLOUD else ShapeAnnotation.ShapeType.POLYGON
    viewModel.createPolygonAnnotation(pageIndex, shapeType, vertices)
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
    pageIndex: Int,
    onRequestTextEdit: (PendingTextEdit) -> Unit
) {
    when (tool) {
        AnnotationViewModel.AnnotationTool.TEXT,
        AnnotationViewModel.AnnotationTool.FREE_TEXT -> {
            onRequestTextEdit(
                PendingTextEdit(
                    pageIndex = pageIndex,
                    rect = android.graphics.RectF(point.x - 60, point.y - 15, point.x + 60, point.y + 15),
                    textType = TextAnnotation.TextType.FREE_TEXT,
                    initialText = "",
                    existingAnnotationId = null
                )
            )
        }
        AnnotationViewModel.AnnotationTool.STICKY_NOTE -> {
            onRequestTextEdit(
                PendingTextEdit(
                    pageIndex = pageIndex,
                    rect = android.graphics.RectF(point.x - 15, point.y - 15, point.x + 15, point.y + 15),
                    textType = TextAnnotation.TextType.STICKY_NOTE,
                    initialText = "",
                    existingAnnotationId = null
                )
            )
        }
        AnnotationViewModel.AnnotationTool.TEXTBOX -> {
            onRequestTextEdit(
                PendingTextEdit(
                    pageIndex = pageIndex,
                    rect = android.graphics.RectF(point.x - 60, point.y - 20, point.x + 60, point.y + 20),
                    textType = TextAnnotation.TextType.TEXTBOX,
                    initialText = "",
                    existingAnnotationId = null
                )
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
