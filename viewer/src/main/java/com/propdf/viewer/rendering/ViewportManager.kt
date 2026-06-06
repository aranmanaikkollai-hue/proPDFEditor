package com.propdf.viewer.rendering

import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tracks the current viewport state and emits changes for reactive rendering.
 *
 * The viewport represents the visible portion of the document in screen coordinates.
 * As the user scrolls/zooms, this updates and triggers tile recalculation.
 */
class ViewportManager {

    private val _viewport = MutableStateFlow(Rect(0, 0, 0, 0))
    val viewport: StateFlow<Rect> = _viewport.asStateFlow()

    private val _zoomLevel = MutableStateFlow(1.0f)
    val zoomLevel: StateFlow<Float> = _zoomLevel.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    /** Total document dimensions in screen pixels */
    private val _documentSize = MutableStateFlow(android.util.Size(0, 0))
    val documentSize: StateFlow<android.util.Size> = _documentSize.asStateFlow()

    /** Scroll position in document coordinates */
    private val _scrollY = MutableStateFlow(0)
    val scrollY: StateFlow<Int> = _scrollY.asStateFlow()

    /**
     * Update viewport dimensions. Called from onSizeChanged or scroll events.
     */
    fun updateViewport(left: Int, top: Int, right: Int, bottom: Int) {
        _viewport.update { Rect(left, top, right, bottom) }
    }

    /**
     * Update zoom level with clamping.
     */
    fun updateZoom(zoom: Float) {
        _zoomLevel.update { zoom.coerceIn(MIN_ZOOM, MAX_ZOOM) }
    }

    /**
     * Update current page based on scroll position.
     */
    fun updateCurrentPage(pageIndex: Int) {
        _currentPage.update { pageIndex.coerceAtLeast(0) }
    }

    /**
     * Update total document size.
     */
    fun updateDocumentSize(width: Int, height: Int) {
        _documentSize.update { android.util.Size(width, height) }
    }

    /**
     * Update scroll position.
     */
    fun updateScrollY(y: Int) {
        _scrollY.update { y.coerceAtLeast(0) }
    }

    /**
     * Get the visible page range based on current viewport and document layout.
     */
    fun getVisiblePageRange(pageHeights: List<Int>): IntRange {
        val viewport = _viewport.value
        val scrollY = _scrollY.value

        var accumulatedHeight = 0
        var firstVisible = -1
        var lastVisible = -1

        pageHeights.forEachIndexed { index, height ->
            val pageTop = accumulatedHeight
            val pageBottom = accumulatedHeight + height
            accumulatedHeight += height

            if (pageBottom > scrollY && pageTop < scrollY + viewport.height()) {
                if (firstVisible == -1) firstVisible = index
                lastVisible = index
            }
        }

        return (firstVisible.coerceAtLeast(0)..(lastVisible.coerceAtLeast(0)))
    }

    companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 10.0f
    }
}
