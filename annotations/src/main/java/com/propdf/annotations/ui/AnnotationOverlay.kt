package com.propdf.annotations.ui

import android.graphics.RectF
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.propdf.annotations.model.*
import com.propdf.annotations.model.Annotation
import com.propdf.annotations.transform.SelectionTool
import kotlinx.coroutines.launch
import kotlin.math.maxOf
import kotlin.math.minOf
import kotlin.math.roundToInt

/**
 * Annotation overlay composable that renders annotations on top of a PDF page.
 * Handles touch input for annotation creation, selection, and manipulation.
 *
 * Integrates with AnnotationViewModel for state management.
 */
@Composable
fun AnnotationOverlay(
    pageIndex: Int,
    pageWidth: Float,
    pageHeight: Float,
    pageScale: Float,
    pageOffset: Offset,
    modifier: Modifier = Modifier,
    viewModel: AnnotationViewModel? = null,
    onAnnotationSelected: ((Annotation?) -> Unit)? = null,
    onAnnotationModified: ((Annotation) -> Unit)? = null
) {
    val density = LocalDensity.current
    val renderer = remember { AnnotationRenderer() }
    val scope = rememberCoroutineScope()

    // Current tool from ViewModel
    val currentTool by viewModel?.currentTool?.collectAsState()
        ?: remember { mutableStateOf(AnnotationViewModel.AnnotationTool.SELECTOR) }

    // Selected annotations from ViewModel
    val selectedAnnotations by viewModel?.selectedAnnotations?.collectAsState()
        ?: remember { mutableStateOf<List<Annotation>>(emptyList()) }

    val selectedAnnotationIds = remember(selectedAnnotations) {
        selectedAnnotations.map { it.id }.toSet()
    }

    // Get annotations for this page from ViewModel
    val annotations = remember(viewModel, pageIndex) {
        viewModel?.getAnnotationsForPage(pageIndex) ?: emptyList()
    }

    // Local drawing state
    var isDrawing by remember { mutableStateOf(false) }
    var currentStroke by remember { mutableStateOf<StrokeAnnotation?>(null) }
    var currentLasso by remember { mutableStateOf<LassoAnnotation?>(null) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var isDraggingSelection by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragStartPos by remember { mutableStateOf<Offset?>(null) }

    // Tool appearance from ViewModel
    val currentColor by viewModel?.currentColor?.collectAsState()
        ?: remember { mutableStateOf(androidx.compose.ui.graphics.Color.Black) }
    val currentStrokeWidth by viewModel?.currentStrokeWidth?.collectAsState()
        ?: remember { mutableStateOf(3f) }
    val currentOpacity by viewModel?.currentOpacity?.collectAsState()
        ?: remember { mutableStateOf(1.0f) }
    val currentPenType by viewModel?.currentPenType?.collectAsState()
        ?: remember { mutableStateOf(StrokeAnnotation.PenType.INK) }

    // Convert Compose Color to Android color int
    val strokeColorInt = remember(currentColor) {
        androidx.compose.ui.graphics.Color(currentColor).let {
            android.graphics.Color.argb(
                (it.alpha * 255).toInt(),
                (it.red * 255).toInt(),
                (it.green * 255).toInt(),
                (it.blue * 255).toInt()
            )
        }
    }

    // Sort annotations by z-index
    val sortedAnnotations = remember(annotations) {
        annotations.sortedBy { it.zIndex }
    }

    // Helper to convert screen point to page point
    fun toPagePoint(screenX: Float, screenY: Float): Offset =
        Offset((screenX - pageOffset.x) / pageScale, (screenY - pageOffset.y) / pageScale)

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInteropFilter { event ->
                    val pagePoint = toPagePoint(event.x, event.y)

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            when (currentTool) {
                                AnnotationViewModel.AnnotationTool.SELECTOR -> {
                                    val hit = findAnnotationAt(annotations, pagePoint.x, pagePoint.y)
                                    if (hit != null) {
                                        viewModel?.selectAnnotation(hit)
                                        onAnnotationSelected?.invoke(hit)
                                        isDraggingSelection = true
                                        dragStartPos = Offset(event.x, event.y)
                                    } else {
                                        viewModel?.deselectAll()
                                        onAnnotationSelected?.invoke(null)
                                    }
                                }
                                AnnotationViewModel.AnnotationTool.PEN,
                                AnnotationViewModel.AnnotationTool.CALLIGRAPHY,
                                AnnotationViewModel.AnnotationTool.MARKER,
                                AnnotationViewModel.AnnotationTool.PENCIL,
                                AnnotationViewModel.AnnotationTool.ERASER -> {
                                    isDrawing = true
                                    val penType = when (currentTool) {
                                        AnnotationViewModel.AnnotationTool.MARKER -> StrokeAnnotation.PenType.MARKER
                                        AnnotationViewModel.AnnotationTool.PENCIL -> StrokeAnnotation.PenType.PENCIL
                                        AnnotationViewModel.AnnotationTool.ERASER -> StrokeAnnotation.PenType.ERASER
                                        AnnotationViewModel.AnnotationTool.CALLIGRAPHY -> StrokeAnnotation.PenType.CALLIGRAPHY
                                        else -> currentPenType
                                    }
                                    val color = when (currentTool) {
                                        AnnotationViewModel.AnnotationTool.ERASER -> android.graphics.Color.TRANSPARENT
                                        else -> strokeColorInt
                                    }
                                    currentStroke = StrokeAnnotation(
                                        pageIndex = pageIndex,
                                        points = listOf(
                                            PointF(
                                                pagePoint.x,
                                                pagePoint.y,
                                                event.pressure.coerceIn(0.1f, 1.0f)
                                            )
                                        ),
                                        strokeWidth = currentStrokeWidth,
                                        penType = penType,
                                        color = color,
                                        opacity = currentOpacity
                                    )
                                }
                                AnnotationViewModel.AnnotationTool.LASSO -> {
                                    isDrawing = true
                                    currentLasso = LassoAnnotation(
                                        pageIndex = pageIndex,
                                        polygon = listOf(PointF(pagePoint.x, pagePoint.y))
                                    )
                                }
                                AnnotationViewModel.AnnotationTool.RECTANGLE,
                                AnnotationViewModel.AnnotationTool.CIRCLE,
                                AnnotationViewModel.AnnotationTool.LINE,
                                AnnotationViewModel.AnnotationTool.ARROW -> {
                                    isDrawing = true
                                    dragStart = Offset(event.x, event.y)
                                    dragCurrent = Offset(event.x, event.y)
                                }
                                AnnotationViewModel.AnnotationTool.TEXT,
                                AnnotationViewModel.AnnotationTool.TEXTBOX,
                                AnnotationViewModel.AnnotationTool.FREE_TEXT -> {
                                    // Text placement handled by tap - create text annotation
                                    val rect = RectF(
                                        pagePoint.x,
                                        pagePoint.y,
                                        pagePoint.x + 200f,
                                        pagePoint.y + 50f
                                    )
                                    viewModel?.createTextAnnotation(
                                        pageIndex = pageIndex,
                                        textType = TextAnnotation.TextType.FREE_TEXT,
                                        rect = rect,
                                        text = ""
                                    )
                                }
                                AnnotationViewModel.AnnotationTool.STAMP,
                                AnnotationViewModel.AnnotationTool.DATE_STAMP -> {
                                    val rect = RectF(
                                        pagePoint.x,
                                        pagePoint.y,
                                        pagePoint.x + 150f,
                                        pagePoint.y + 50f
                                    )
                                    val stampType = when (currentTool) {
                                        AnnotationViewModel.AnnotationTool.DATE_STAMP -> StampAnnotation.StampType.DATE
                                        else -> StampAnnotation.StampType.APPROVED
                                    }
                                    viewModel?.createStampAnnotation(
                                        pageIndex = pageIndex,
                                        stampType = stampType,
                                        rect = rect
                                    )
                                }
                                AnnotationViewModel.AnnotationTool.HIGHLIGHT,
                                AnnotationViewModel.AnnotationTool.UNDERLINE,
                                AnnotationViewModel.AnnotationTool.STRIKEOUT,
                                AnnotationViewModel.AnnotationTool.SQUIGGLY -> {
                                    // Highlight tools require text selection - handled differently
                                }
                                else -> {}
                            }
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            when {
                                isDraggingSelection && dragStartPos != null -> {
                                    val dx = event.x - dragStartPos!!.x
                                    val dy = event.y - dragStartPos!!.y
                                    dragOffset = Offset(dx, dy)
                                }
                                isDrawing && currentStroke != null -> {
                                    val point = PointF(
                                        pagePoint.x,
                                        pagePoint.y,
                                        event.pressure.coerceIn(0.1f, 1.0f)
                                    )
                                    currentStroke = currentStroke!!.copy(
                                        points = currentStroke!!.points + point
                                    )
                                }
                                isDrawing && currentLasso != null -> {
                                    val point = PointF(pagePoint.x, pagePoint.y)
                                    currentLasso = currentLasso!!.copy(
                                        polygon = currentLasso!!.polygon + point
                                    )
                                }
                                isDrawing && dragCurrent != null -> {
                                    dragCurrent = Offset(event.x, event.y)
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            when {
                                isDraggingSelection -> {
                                    if (dragOffset != Offset.Zero) {
                                        val dx = dragOffset.x / pageScale
                                        val dy = dragOffset.y / pageScale
                                        viewModel?.moveSelected(dx, dy)
                                    }
                                    isDraggingSelection = false
                                    dragStartPos = null
                                    dragOffset = Offset.Zero
                                }
                                isDrawing && currentStroke != null -> {
                                    val finalStroke = currentStroke!!
                                    if (finalStroke.points.size >= 2) {
                                        viewModel?.createAnnotation(finalStroke)
                                        onAnnotationModified?.invoke(finalStroke)
                                    }
                                    currentStroke = null
                                    isDrawing = false
                                }
                                isDrawing && currentLasso != null -> {
                                    val closedLasso = currentLasso!!.closePolygon()
                                    viewModel?.selectByLasso(closedLasso)
                                    currentLasso = null
                                    isDrawing = false
                                }
                                isDrawing && dragStart != null && dragCurrent != null -> {
                                    val startPage = toPagePoint(dragStart!!.x, dragStart!!.y)
                                    val endPage = toPagePoint(dragCurrent!!.x, dragCurrent!!.y)
                                    val rect = RectF(
                                        minOf(startPage.x, endPage.x),
                                        minOf(startPage.y, endPage.y),
                                        maxOf(startPage.x, endPage.x),
                                        maxOf(startPage.y, endPage.y)
                                    )

                                    val shapeType = when (currentTool) {
                                        AnnotationViewModel.AnnotationTool.RECTANGLE -> ShapeAnnotation.ShapeType.RECTANGLE
                                        AnnotationViewModel.AnnotationTool.CIRCLE -> ShapeAnnotation.ShapeType.CIRCLE
                                        AnnotationViewModel.AnnotationTool.LINE -> ShapeAnnotation.ShapeType.LINE
                                        AnnotationViewModel.AnnotationTool.ARROW -> ShapeAnnotation.ShapeType.ARROW
                                        else -> null
                                    }
                                    shapeType?.let {
                                        viewModel?.createShapeAnnotation(pageIndex, it, rect)
                                    }
                                    dragStart = null
                                    dragCurrent = null
                                    isDrawing = false
                                }
                            }
                            true
                        }
                        else -> false
                    }
                }
        ) {
            // Render all annotations
            sortedAnnotations.forEach { annotation ->
                renderer.render(this, annotation, pageScale, pageOffset)
            }

            // Render current stroke
            currentStroke?.let { stroke ->
                renderer.render(this, stroke, pageScale, pageOffset)
            }

            // Render current lasso
            currentLasso?.let { lasso ->
                renderer.render(this, lasso, pageScale, pageOffset)
            }

            // Render shape preview
            if (isDrawing && dragStart != null && dragCurrent != null) {
                val startPage = toPagePoint(dragStart!!.x, dragStart!!.y)
                val endPage = toPagePoint(dragCurrent!!.x, dragCurrent!!.y)
                val previewRect = RectF(
                    minOf(startPage.x, endPage.x),
                    minOf(startPage.y, endPage.y),
                    maxOf(startPage.x, endPage.x),
                    maxOf(startPage.y, endPage.y)
                )

                val previewShapeType = when (currentTool) {
                    AnnotationViewModel.AnnotationTool.RECTANGLE -> ShapeAnnotation.ShapeType.RECTANGLE
                    AnnotationViewModel.AnnotationTool.CIRCLE -> ShapeAnnotation.ShapeType.CIRCLE
                    AnnotationViewModel.AnnotationTool.LINE -> ShapeAnnotation.ShapeType.LINE
                    AnnotationViewModel.AnnotationTool.ARROW -> ShapeAnnotation.ShapeType.ARROW
                    else -> null
                }
                previewShapeType?.let { shapeType ->
                    val preview = ShapeAnnotation(
                        pageIndex = pageIndex,
                        shapeType = shapeType,
                        rect = previewRect,
                        color = strokeColorInt,
                        strokeWidth = currentStrokeWidth
                    )
                    renderer.render(this, preview, pageScale, pageOffset)
                }
            }
        }

        // Selection drag ghost overlay
        if (isDraggingSelection && dragOffset != Offset.Zero) {
            sortedAnnotations
                .filter { selectedAnnotationIds.contains(it.id) }
                .forEach { annotation ->
                    val bounds = annotation.getBounds()
                    val screenLeft = bounds.left * pageScale + pageOffset.x + dragOffset.x
                    val screenTop = bounds.top * pageScale + pageOffset.y + dragOffset.y
                    val screenWidth = bounds.width() * pageScale
                    val screenHeight = bounds.height() * pageScale

                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    screenLeft.roundToInt(),
                                    screenTop.roundToInt()
                                )
                            }
                            .size(
                                with(density) { screenWidth.toDp() },
                                with(density) { screenHeight.toDp() }
                            )
                            .background(Color(0x332196F3))
                    )
                }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            renderer.clearCache()
        }
    }
}

/**
 * Find the topmost annotation at the given page coordinates.
 */
private fun findAnnotationAt(
    annotations: List<Annotation>,
    x: Float, y: Float,
    tolerance: Float = 10f
): Annotation? {
    return annotations
        .filter { it.isVisible }
        .sortedByDescending { it.zIndex }
        .firstOrNull { it.hitTest(x, y, tolerance) }
}
