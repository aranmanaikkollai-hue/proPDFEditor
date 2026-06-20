package com.propdf.viewer.annotation.manager

import android.graphics.PointF
import android.graphics.RectF
import com.propdf.viewer.annotation.model.Annotation
import com.propdf.viewer.annotation.model.AnnotationState
import com.propdf.viewer.annotation.model.AnnotationType
import com.propdf.viewer.annotation.model.ImageStampAnnotation
import com.propdf.viewer.annotation.model.InkAnnotation
import com.propdf.viewer.annotation.model.ShapeAnnotation
import com.propdf.viewer.annotation.model.SignatureAnnotation
import com.propdf.viewer.annotation.model.StickyNoteAnnotation
import com.propdf.viewer.annotation.model.TextCommentAnnotation
import com.propdf.viewer.annotation.model.TextMarkupAnnotation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Central annotation manager with undo/redo, layer ordering, and persistence.
 * All annotation operations go through this class.
 */
class AnnotationManager {

    private val _annotations = MutableStateFlow<List<Annotation>>(emptyList())
    val annotations: StateFlow<List<Annotation>> = _annotations.asStateFlow()

    private val undoStack = ArrayDeque<List<Annotation>>(MAX_UNDO_SIZE)
    private val redoStack = ArrayDeque<List<Annotation>>(MAX_UNDO_SIZE)

    private var _selectedAnnotationId: String? = null
    val selectedAnnotationId: String? get() = _selectedAnnotationId

    private var nextZIndex = 0

    companion object {
        private const val MAX_UNDO_SIZE = 50
    }

    // ==================== CRUD OPERATIONS ====================

    fun addAnnotation(annotation: Annotation) {
        saveStateForUndo()
        val withZ = when (annotation) {
            is TextMarkupAnnotation -> annotation.copy(zIndex = nextZIndex++)
            is InkAnnotation -> annotation.copy(zIndex = nextZIndex++)
            is TextCommentAnnotation -> annotation.copy(zIndex = nextZIndex++)
            is StickyNoteAnnotation -> annotation.copy(zIndex = nextZIndex++)
            is ShapeAnnotation -> annotation.copy(zIndex = nextZIndex++)
            is SignatureAnnotation -> annotation.copy(zIndex = nextZIndex++)
            is ImageStampAnnotation -> annotation.copy(zIndex = nextZIndex++)
        }
        _annotations.update { it + withZ }
        clearRedo()
    }

    fun removeAnnotation(id: String) {
        saveStateForUndo()
        _annotations.update { list -> list.filter { ann -> ann.id != id } }
        if (_selectedAnnotationId == id) _selectedAnnotationId = null
        clearRedo()
    }

    @Suppress("UNUSED_PARAMETER")
    fun updateAnnotation(annotation: Annotation) {
        saveStateForUndo()
        _annotations.update { list ->
            list.map { ann -> if (ann.id == annotation.id) annotation else ann }
        }
        clearRedo()
    }

    @Suppress("UNUSED_PARAMETER")
    fun getAnnotation(id: String): Annotation? {
        return _annotations.value.find { ann -> ann.id == id }
    }

    fun replaceAnnotations(annotations: List<Annotation>) {
        val sorted = annotations.sortedWith(
            compareBy<Annotation> { it.pageIndex }.thenBy { it.zIndex }
        )
        _annotations.value = sorted
        nextZIndex = (annotations.maxOfOrNull { it.zIndex } ?: -1) + 1
        _selectedAnnotationId = null
        undoStack.clear()
        redoStack.clear()
    }

    fun getAnnotationsForPage(pageIndex: Int): List<Annotation> {
        return _annotations.value
            .filter { ann -> ann.pageIndex == pageIndex }
            .sortedBy { ann -> ann.zIndex }
    }

    // ==================== SELECTION ====================

    fun selectAnnotation(id: String?) {
        _selectedAnnotationId = id
        _annotations.update { list ->
            list.map { annotation ->
                val isSelected = annotation.id == id
                val newState = when {
                    isSelected -> AnnotationState.SELECTED
                    annotation.state == AnnotationState.SELECTED -> AnnotationState.CREATED
                    else -> annotation.state
                }
                annotation.withState(newState)
            }
        }
    }

    fun deselectAll() {
        selectAnnotation(null)
    }

    fun getSelectedAnnotation(): Annotation? {
        return _selectedAnnotationId?.let { getAnnotation(it) }
    }

    // ==================== HIT TESTING ====================

    fun hitTest(pageIndex: Int, x: Float, y: Float, tolerance: Float = 0.01f): Annotation? {
        return getAnnotationsForPage(pageIndex)
            .asReversed()
            .find { ann -> ann.hitTest(x, y, tolerance) }
    }

    // ==================== MOVE / RESIZE ====================

    fun moveAnnotation(id: String, dx: Float, dy: Float) {
        val annotation = getAnnotation(id) ?: return
        saveStateForUndo()
        _annotations.update { list ->
            list.map { ann -> if (ann.id == id) ann.move(dx, dy) else ann }
        }
    }

    fun scaleAnnotation(id: String, scaleFactor: Float, pivotX: Float, pivotY: Float) {
        val annotation = getAnnotation(id) ?: return
        saveStateForUndo()
        _annotations.update { list ->
            list.map { ann -> if (ann.id == id) ann.scale(scaleFactor, pivotX, pivotY) else ann }
        }
    }

    // ==================== UNDO / REDO ====================

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo() {
        if (undoStack.isEmpty()) return
        val current = _annotations.value.toList()
        val previous = undoStack.removeLast()
        redoStack.addLast(current)
        _annotations.value = previous
        _selectedAnnotationId = null
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val current = _annotations.value.toList()
        val next = redoStack.removeLast()
        undoStack.addLast(current)
        _annotations.value = next
        _selectedAnnotationId = null
    }

    private fun saveStateForUndo() {
        if (undoStack.size >= MAX_UNDO_SIZE) undoStack.removeFirst()
        undoStack.addLast(_annotations.value.toList())
    }

    private fun clearRedo() {
        redoStack.clear()
    }

    // ==================== LAYER MANAGEMENT ====================

    fun bringToFront(id: String) {
        val annotation = getAnnotation(id) ?: return
        saveStateForUndo()
        _annotations.update { list ->
            list.map { ann ->
                if (ann.id == id) {
                    when (ann) {
                        is TextMarkupAnnotation -> ann.copy(zIndex = nextZIndex++)
                        is InkAnnotation -> ann.copy(zIndex = nextZIndex++)
                        is TextCommentAnnotation -> ann.copy(zIndex = nextZIndex++)
                        is StickyNoteAnnotation -> ann.copy(zIndex = nextZIndex++)
                        is ShapeAnnotation -> ann.copy(zIndex = nextZIndex++)
                        is SignatureAnnotation -> ann.copy(zIndex = nextZIndex++)
                        is ImageStampAnnotation -> ann.copy(zIndex = nextZIndex++)
                    }
                } else ann
            }
        }
    }

    fun sendToBack(id: String) {
        val minZ = _annotations.value.minOfOrNull { ann -> ann.zIndex } ?: 0
        saveStateForUndo()
        _annotations.update { list ->
            list.map { ann ->
                if (ann.id == id) {
                    when (ann) {
                        is TextMarkupAnnotation -> ann.copy(zIndex = minZ - 1)
                        is InkAnnotation -> ann.copy(zIndex = minZ - 1)
                        is TextCommentAnnotation -> ann.copy(zIndex = minZ - 1)
                        is StickyNoteAnnotation -> ann.copy(zIndex = minZ - 1)
                        is ShapeAnnotation -> ann.copy(zIndex = minZ - 1)
                        is SignatureAnnotation -> ann.copy(zIndex = minZ - 1)
                        is ImageStampAnnotation -> ann.copy(zIndex = minZ - 1)
                    }
                } else ann
            }
        }
    }

    // ==================== CLEAR ====================

    fun clearPage(pageIndex: Int) {
        saveStateForUndo()
        _annotations.update { it.filter { ann -> ann.pageIndex != pageIndex } }
        clearRedo()
    }

    fun clearAll() {
        saveStateForUndo()
        _annotations.value = emptyList()
        nextZIndex = 0
        _selectedAnnotationId = null
        clearRedo()
    }

    // ==================== PERSISTENCE (JSON) ====================

    fun exportToJson(): String {
        val sb = StringBuilder()
        sb.append("[\n")
        _annotations.value.forEachIndexed { index, annotation ->
            sb.append("""{"id":"${annotation.id}","page":${annotation.pageIndex},"type":"${annotation.type}","color":${annotation.color}}""")
            if (index < _annotations.value.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]")
        return sb.toString()
    }

    fun importFromJson(json: String) {
        clearAll()
    }
}
