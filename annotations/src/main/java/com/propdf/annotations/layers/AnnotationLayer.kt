package com.propdf.annotations.layers

import com.propdf.annotations.model.Annotation

/**
 * A layer containing a group of annotations with z-order control.
 */
data class AnnotationLayer(
    val id: String,
    val name: String,
    val annotations: MutableList<Annotation> = mutableListOf(),
    var isVisible: Boolean = true,
    var isLocked: Boolean = false,
    var opacity: Float = 1.0f,
    val zIndex: Int = 0
) {
    fun addAnnotation(annotation: Annotation) {
        if (!isLocked) {
            annotations.add(annotation)
            sortByZIndex()
        }
    }

    fun removeAnnotation(annotationId: String): Boolean {
        if (isLocked) return false
        return annotations.removeIf { it.id == annotationId }
    }

    fun getAnnotation(id: String): Annotation? = annotations.find { it.id == id }

    fun updateAnnotation(updated: Annotation) {
        if (isLocked) return
        val index = annotations.indexOfFirst { it.id == updated.id }
        if (index != -1) {
            annotations[index] = updated
        }
    }

    fun bringToFront(annotationId: String) {
        val annotation = annotations.find { it.id == annotationId } ?: return
        val maxZ = annotations.maxOfOrNull { it.zIndex } ?: 0
        updateAnnotation(annotation.copy(zIndex = maxZ + 1))
        sortByZIndex()
    }

    fun sendToBack(annotationId: String) {
        val annotation = annotations.find { it.id == annotationId } ?: return
        val minZ = annotations.minOfOrNull { it.zIndex } ?: 0
        updateAnnotation(annotation.copy(zIndex = minZ - 1))
        sortByZIndex()
    }

    private fun sortByZIndex() {
        annotations.sortBy { it.zIndex }
    }

    fun getVisibleAnnotations(): List<Annotation> {
        return annotations.filter { it.isVisible && this.isVisible }
    }
}
