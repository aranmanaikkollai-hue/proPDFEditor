package com.propdf.annotations.transform

import android.graphics.RectF
import com.propdf.annotations.model.Annotation

/**
 * Multi-selection and bounding box management for annotations.
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

    fun select(annotation: Annotation, additive: Boolean = false) {
        if (!additive) {
            _selectedAnnotations.clear()
        }
        if (_selectedAnnotations.none { it.id == annotation.id }) {
            _selectedAnnotations.add(annotation)
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

    fun getSelectionHandlePositions(): List<Pair<Float, Float>> {
        val bounds = selectionBounds ?: return emptyList()
        return listOf(
            bounds.left to bounds.top,       // Top-left
            bounds.centerX() to bounds.top,   // Top-center
            bounds.right to bounds.top,       // Top-right
            bounds.right to bounds.centerY(), // Right-center
            bounds.right to bounds.bottom,    // Bottom-right
            bounds.centerX() to bounds.bottom, // Bottom-center
            bounds.left to bounds.bottom,     // Bottom-left
            bounds.left to bounds.centerY()   // Left-center
        )
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
}
