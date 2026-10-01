package com.propdf.viewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val ThumbSize = 48.dp

/**
 * Side page scrubber: a draggable handle on the right edge plus a pill that
 * shows "page / total". It appears while the document scrolls or is dragged
 * and fades out after a short idle period.
 *
 * [scrollFraction] is the current position, 0f (top) to 1f (end).
 * [onScrollToFraction] is called with the new 0f..1f position while dragging.
 * [onDragStart] lets the caller stop any running fling.
 */
@Composable
fun PageFastScroller(
    currentPage: Int,
    totalPages: Int,
    scrollFraction: Float,
    onScrollToFraction: (Float) -> Unit,
    onDragStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (totalPages <= 1) return

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(scrollFraction, dragging) {
        visible = true
        if (!dragging) {
            delay(1800)
            visible = false
        }
    }

    val shownFraction = (if (dragging) dragFraction else scrollFraction).coerceIn(0f, 1f)
    val latestFraction by rememberUpdatedState(scrollFraction)
    val latestOnScroll by rememberUpdatedState(onScrollToFraction)
    val latestOnDragStart by rememberUpdatedState(onDragStart)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val trackPx = with(LocalDensity.current) { (maxHeight - ThumbSize).toPx() }.coerceAtLeast(1f)

        AnimatedVisibility(
            visible = visible || dragging,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (shownFraction * trackPx).roundToInt()) }
        ) {
            Row(
                modifier = Modifier.padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = "${currentPage + 1} / $totalPages",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .size(ThumbSize)
                        .semantics { contentDescription = "Page scrubber, page ${currentPage + 1} of $totalPages" }
                        .pointerInput(trackPx) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragFraction = latestFraction
                                    dragging = true
                                    latestOnDragStart()
                                },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    dragFraction = (dragFraction + dragAmount / trackPx).coerceIn(0f, 1f)
                                    latestOnScroll(dragFraction)
                                }
                            )
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.DragIndicator,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}
