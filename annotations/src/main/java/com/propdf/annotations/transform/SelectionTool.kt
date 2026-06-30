package com.propdf.annotations.transform

import android.graphics.RectF
import com.propdf.annotations.model.Annotation
import com.propdf.annotations.model.LassoAnnotation
import com.propdf.annotations.model.PointF

/**
 * Multi-selection and bounding box management for annotations.
 * Supports tap selection, marquee selection, and lasso selection.
 * Thread-safe: uses internal mutable state, external callers should synchronize.
 */
class SelectionTool {

    private val _selectedAnnotations = mutableListOf<Annotation>()
    val selectedAnnotations: List<Annotation> get() = _selectedAnnotations.toList()

    val selectionBounds: RectF?
        get() = if (_selectedAnnotations.isEmpty()) null else {
            val bounds = _selectedAnnotations.map { it.getBounds() }
            val result = RectF(bounds[0])
            bounds.drop(1).forEach { result.union(it) }
            result
        }

    val selectionCenter: PointF?
        get() = selectionBounds?.let { PointF(it.centerX(), it.centerY()) }

    val hasSelection: Boolean get() = _selectedAnnotations.isNotEmpty()
    val selectionCount: Int get() = _selectedAnnotations.size

    /**
     * Select an annotation. If additive is false, clears previous selection.
     */
    fun select(annotation: Annotation, additive: Boolean = false) {
        if (!additive) {
            _selectedAnnotations.clear()
        }
        if (_selectedAnnotations.none { it.id == annotation.id }) {
            _selectedAnnotations.add(annotation)
        }
    }

    /**
     * Select multiple annotations at once.
     */
    fun selectAll(annotations: List<Annotation>) {
        _selectedAnnotations.clear()
        _selectedAnnotations.addAll(annotations)
    }

    /**
     * Select annotations within a lasso polygon.
     */
    fun selectByLasso(lasso: LassoAnnotation, allAnnotations: List<Annotation>) {
        _selectedAnnotations.clear()
        allAnnotations.forEach { annotation ->
            if (lasso.containsAnnotation(annotation)) {
                _selectedAnnotations.add(annotation)
            }
        }
    }

    /**
     * Select annotations within a rectangular bounds.
     */
    fun selectByRect(rect: RectF, allAnnotations: List<Annotation>) {
        _selectedAnnotations.clear()
        allAnnotations.forEach { annotation ->
            val bounds = annotation.getBounds()
            if (rect.contains(bounds)) {
                _selectedAnnotations.add(annotation)
            }
        }
    }

    fun deselect(annotation: Annotation) {
        _selectedAnnotations.removeIf { it.id == annotation.id }
    }

    fun deselectAll() {
        _selectedAnnotations.clear()
    }

    fun isSelected(annotationId: String): Boolean {
        return _selectedAnnotations.any { it.id == annotationId }
    }

    fun isSelected(annotation: Annotation): Boolean = isSelected(annotation.id)

    /**
     * Get resize handle positions for the selection bounding box.
     * Returns 8 handles: corners and edge midpoints.
     */
    fun getSelectionHandlePositions(): List<Pair<Float, Float>> {
        val bounds = selectionBounds ?: return emptyList()
        return listOf(
            bounds.left to bounds.top,           // Top-left (0)
            bounds.centerX() to bounds.top,       // Top-center (1)
            bounds.right to bounds.top,           // Top-right (2)
            bounds.right to bounds.centerY(),     // Right-center (3)
            bounds.right to bounds.bottom,        // Bottom-right (4)
            bounds.centerX() to bounds.bottom,    // Bottom-center (5)
            bounds.left to bounds.bottom,         // Bottom-left (6)
            bounds.left to bounds.centerY()       // Left-center (7)
        )
    }

    /**
     * Get the handle index at a given position (for resize operations).
     * Returns -1 if no handle is at the position.
     */
    fun getHandleAt(x: Float, y: Float, tolerance: Float = 20f): Int {
        val handles = getSelectionHandlePositions()
        handles.forEachIndexed { index, (hx, hy) ->
            if (kotlin.math.hypot(x - hx, y - hy) <= tolerance) {
                return index
            }
        }
        return -1
    }

    fun moveSelection(dx: Float, dy: Float): List<Annotation> {
        return _selectedAnnotations.map { it.translate(dx, dy) }
    }

    fun scaleSelection(factor: Float, pivotX: Float, pivotY: Float): List<Annotation> {
        return _selectedAnnotations.map { it.scale(factor, pivotX, pivotY) }
    }

    fun rotateSelection(degrees: Float, pivotX: Float, pivotY: Float): List<Annotation> {
        return _selectedAnnotations.map { it.rotate(degrees, pivotX, pivotY) }
    }

    fun deleteSelection(): List<Annotation> {
        val deleted = _selectedAnnotations.toList()
        _selectedAnnotations.clear()
        return deleted
    }

    /**
     * Get the top-most annotation at a position (for tap selection).
     */
    fun getAnnotationAt(x: Float, y: Float, annotations: List<Annotation>): Annotation? {
        return annotations
            .filter { it.hitTest(x, y) }
            .maxByOrNull { it.zIndex }
    }

    /**
     * Cycle selection through overlapping annotations.
     */
    fun cycleSelectionAt(x: Float, y: Float, annotations: List<Annotation>): Annotation? {
        val hits = annotations.filter { it.hitTest(x, y) }.sortedByDescending { it.zIndex }
        if (hits.isEmpty()) return null

        val currentIndex = _selectedAnnotations.firstOrNull()?.let { selected ->
            hits.indexOfFirst { it.id == selected.id }
        } ?: -1

        val nextIndex = (currentIndex + 1) % hits.size
        val next = hits[nextIndex]
        _selectedAnnotations.clear()
        _selectedAnnotations.add(next)
        return next
    }
}
