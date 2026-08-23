package com.propdf.scanner.ui

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.hypot

/**
 * Full-screen corner-adjustment step: shows [sourceBitmap] (the page's uncropped
 * original) with 4 draggable handles seeded from [initialCorners], and calls
 * [onConfirm] with the user's final corners (in [sourceBitmap]'s pixel coordinates)
 * so the caller can re-run perspective correction.
 *
 * Automatic edge detection (DocumentScannerEngine.detectDocumentCorners) is good but
 * not perfect -- this is the manual fallback the task explicitly calls "essential when
 * automatic detection is imperfect", and it previously didn't exist: there was no UI
 * anywhere that let the user see or move the detected quad after capture.
 */
@Composable
fun CornerAdjustDialog(
    sourceBitmap: Bitmap,
    initialCorners: List<PointF>,
    onConfirm: (List<PointF>) -> Unit,
    onDismiss: () -> Unit
) {
    var imageDisplayRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var handles by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var draggingIndex by remember { mutableStateOf(-1) }

    LaunchedEffect(imageDisplayRect) {
        val rect = imageDisplayRect ?: return@LaunchedEffect
        if (handles.isEmpty() && initialCorners.size == 4) {
            handles = initialCorners.map { p ->
                Offset(
                    rect.left + (p.x / sourceBitmap.width) * rect.width,
                    rect.top + (p.y / sourceBitmap.height) * rect.height
                )
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Adjust Corners") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val rect = imageDisplayRect
                            if (rect != null && handles.size == 4) {
                                val corners = handles.map { h ->
                                    PointF(
                                        ((h.x - rect.left) / rect.width * sourceBitmap.width)
                                            .coerceIn(0f, sourceBitmap.width.toFloat()),
                                        ((h.y - rect.top) / rect.height * sourceBitmap.height)
                                            .coerceIn(0f, sourceBitmap.height.toFloat())
                                    )
                                }
                                onConfirm(corners)
                            }
                        }) {
                            Icon(Icons.Default.Check, contentDescription = "Apply", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = sourceBitmap.asImageBitmap(),
                        contentDescription = "Scanned page",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .onDisplayedImageRect(sourceBitmap) { rect -> imageDisplayRect = rect }
                    )

                    if (handles.size == 4) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(handles) {
                                    detectDragGestures(
                                        onDragStart = { pos ->
                                            val nearest = handles.indices.minByOrNull { i ->
                                                hypot(handles[i].x - pos.x, handles[i].y - pos.y)
                                            }
                                            draggingIndex = if (nearest != null &&
                                                hypot(handles[nearest].x - pos.x, handles[nearest].y - pos.y) < 60f
                                            ) nearest else -1
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val idx = draggingIndex
                                            val rect = imageDisplayRect
                                            if (idx in 0..3 && rect != null) {
                                                val updated = handles.toMutableList()
                                                val newPoint = updated[idx] + dragAmount
                                                updated[idx] = Offset(
                                                    newPoint.x.coerceIn(rect.left, rect.right),
                                                    newPoint.y.coerceIn(rect.top, rect.bottom)
                                                )
                                                handles = updated
                                            }
                                        },
                                        onDragEnd = { draggingIndex = -1 }
                                    )
                                }
                        ) {
                            val path = Path().apply {
                                moveTo(handles[0].x, handles[0].y)
                                lineTo(handles[1].x, handles[1].y)
                                lineTo(handles[2].x, handles[2].y)
                                lineTo(handles[3].x, handles[3].y)
                                close()
                            }
                            drawPath(path, color = Color(0xFF4CAF50), style = Stroke(width = 3.dp.toPx()))
                            handles.forEach { h ->
                                drawCircle(color = Color.White, radius = 14.dp.toPx(), center = h)
                                drawCircle(color = Color(0xFF4CAF50), radius = 10.dp.toPx(), center = h)
                            }
                        }
                    }
                }

                Text(
                    text = "Drag the corners to match the document edges",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/**
 * Reports the actual displayed rect of the bitmap within an Image composable using
 * ContentScale.Fit (i.e. accounting for letterboxing), since drag/handle math needs to
 * happen in the same coordinate space the image is actually drawn in, not the full
 * layout bounds of the composable.
 */
private fun Modifier.onDisplayedImageRect(
    bitmap: Bitmap,
    onRect: (androidx.compose.ui.geometry.Rect) -> Unit
): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val boxW = placeable.width.toFloat()
        val boxH = placeable.height.toFloat()
        val bmpAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val boxAspect = if (boxH > 0f) boxW / boxH else 1f
        val (w, h) = if (bmpAspect > boxAspect) {
            boxW to boxW / bmpAspect
        } else {
            boxH * bmpAspect to boxH
        }
        val left = (boxW - w) / 2f
        val top = (boxH - h) / 2f
        onRect(androidx.compose.ui.geometry.Rect(left, top, left + w, top + h))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
)
