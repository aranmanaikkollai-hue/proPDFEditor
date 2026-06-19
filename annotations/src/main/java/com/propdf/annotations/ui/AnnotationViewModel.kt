package com.propdf.annotations.ui

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.annotations.history.HistoryManager
import com.propdf.annotations.layers.LayerManager
import com.propdf.annotations.model.Annotation
import com.propdf.annotations.persistence.AnnotationRepository
import com.propdf.annotations.transform.AnnotationTransformer
import com.propdf.annotations.transform.SelectionTool
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hilt ViewModel for annotation operations.
 */
@HiltViewModel
class AnnotationViewModel @Inject constructor(
    private val repository: AnnotationRepository,
    private val layerManager: LayerManager,
    private val historyManager: HistoryManager,
    private val transformer: AnnotationTransformer
) : ViewModel() {

    private val _currentTool = MutableStateFlow(AnnotationTool.PEN)
    val currentTool: StateFlow<AnnotationTool> = _currentTool.asStateFlow()

    private val _currentColor = MutableStateFlow(Color.Black)
    val currentColor: StateFlow<Color> = _currentColor.asStateFlow()

    private val _currentStrokeWidth = MutableStateFlow(3f)
    val currentStrokeWidth: StateFlow<Float> = _currentStrokeWidth.asStateFlow()

    private val _selectedAnnotation = MutableStateFlow<Annotation?>(null)
    val selectedAnnotation: StateFlow<Annotation?> = _selectedAnnotation.asStateFlow()

    private val selectionTool = SelectionTool()

    val layers = layerManager.layers
    val activeLayerId = layerManager.activeLayerId
    val canUndo = historyManager.canUndo
    val canRedo = historyManager.canRedo

    private var currentDocumentId: String? = null

    fun initializeDocument(documentId: String) {
        currentDocumentId = documentId
        viewModelScope.launch {
            layerManager.createLayer("Default")
            repository.getAnnotationsForDocument(documentId).collect { annotations: List<com.propdf.annotations.model.Annotation> ->
                annotations.forEach { annotation: com.propdf.annotations.model.Annotation ->
                    layerManager.getActiveLayer()?.addAnnotation(annotation)
                }
            }
        }
    }

    fun setTool(tool: AnnotationTool) {
        _currentTool.update { tool }
        if (tool != AnnotationTool.SELECTOR) {
            selectionTool.deselectAll()
            _selectedAnnotation.update { null }
        }
    }

    fun setColor(color: Color) {
        _currentColor.update { color }
    }

    fun setStrokeWidth(width: Float) {
        _currentStrokeWidth.update { width }
    }

    fun createAnnotation(annotation: Annotation) {
        historyManager.addAnnotation(annotation)
        _selectedAnnotation.update { null }
        saveAnnotations()
    }

    fun selectAnnotation(annotation: Annotation) {
        selectionTool.select(annotation)
        _selectedAnnotation.update { annotation }
    }

    fun deselectAll() {
        selectionTool.deselectAll()
        _selectedAnnotation.update { null }
    }

    fun deleteSelected() {
        val selected = selectionTool.deleteSelection()
        selected.forEach { historyManager.deleteAnnotation(it) }
        _selectedAnnotation.update { null }
        saveAnnotations()
    }

    fun changeSelectedColor(color: Color) {
        val selected = selectionTool.selectedAnnotations
        val newColor = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
        selected.forEach { old ->
            val updated = transformer.changeColor(old, newColor)
            historyManager.modifyAnnotation(old, updated)
        }
        saveAnnotations()
    }

    fun changeSelectedOpacity(opacity: Float) {
        val selected = selectionTool.selectedAnnotations
        selected.forEach { old ->
            val updated = transformer.changeOpacity(old, opacity)
            historyManager.modifyAnnotation(old, updated)
        }
        saveAnnotations()
    }

    fun moveSelected(dx: Float, dy: Float) {
        val moved = selectionTool.moveSelection(dx, dy)
        val selected = selectionTool.selectedAnnotations
        selected.zip(moved).forEach { (old, new) ->
            historyManager.modifyAnnotation(old, new)
        }
        saveAnnotations()
    }

    fun undo() {
        historyManager.undo()
        saveAnnotations()
    }

    fun redo() {
        historyManager.redo()
        saveAnnotations()
    }

    fun createLayer(name: String) {
        layerManager.createLayer(name)
    }

    fun setActiveLayer(layerId: String) {
        layerManager.setActiveLayer(layerId)
    }

    private fun saveAnnotations() {
        val docId = currentDocumentId ?: return
        viewModelScope.launch {
            val allAnnotations = layerManager.getAllAnnotations()
            repository.saveAnnotations(docId, allAnnotations)
        }
    }
}
