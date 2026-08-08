package com.propdf.viewer.ui

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Accessible wrapper around PDFCanvas that adds TalkBack support
 * for gesture-based navigation and zoom announcements.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AccessiblePDFCanvas(
    zoomLevel: Float,
    onZoomChange: (Float) -> Unit,
    onViewportChange: (Float, Float, Float, Float) -> Unit,
    currentPage: Int,
    totalPages: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var lastAnnouncedPage by remember { mutableIntStateOf(-1) }

    // Announce page changes to TalkBack
    LaunchedEffect(currentPage) {
        if (currentPage != lastAnnouncedPage) {
            lastAnnouncedPage = currentPage
            // TalkBack will announce via semantics update
        }
    }

    Box(
        modifier = modifier
            .semantics {
                // Make the canvas a scrollable container
                scrollBy(action = { x, y ->
                    onViewportChange(x, y, x + 100, y + 100)
                    true
                })
                // Page navigation actions
                customActions = listOf(
                    CustomAccessibilityAction("Previous page") {
                        if (currentPage > 0) {
                            onViewportChange(0f, 0f, 100f, 100f)
                            true
                        } else false
                    },
                    CustomAccessibilityAction("Next page") {
                        if (currentPage < totalPages - 1) {
                            onViewportChange(0f, 0f, 100f, 100f)
                            true
                        } else false
                    },
                    CustomAccessibilityAction("Zoom in") {
                        onZoomChange((zoomLevel * 1.2f).coerceAtMost(5.0f))
                        true
                    },
                    CustomAccessibilityAction("Zoom out") {
                        onZoomChange((zoomLevel / 1.2f).coerceAtLeast(0.1f))
                        true
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newZoom = (zoomLevel * zoom).coerceIn(0.1f, 5.0f)
                    onZoomChange(newZoom)
                }
            }
    ) {
        PDFCanvas(
            zoomLevel = zoomLevel,
            onZoomChange = onZoomChange,
            onViewportChange = { left, top, right, bottom ->
                onViewportChange(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
