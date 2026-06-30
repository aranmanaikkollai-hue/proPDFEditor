package com.propdf.annotations.ui

import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.annotations.history.HistoryManager
import com.propdf.annotations.layers.LayerManager
import com.propdf.annotations.model.*
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
 * Coordinates between UI, history, layers, and persistence.
 */
@HiltViewModel
class AnnotationViewModel @Inject constructor(
    private val repository: AnnotationRepository,
    val layerManager: LayerManager,
    val historyManager: HistoryManager,
    private val transformer: AnnotationTransformer
) : ViewModel() {

    // Tool state
    private val _currentTool = MutableStateFlow(AnnotationTool.PEN)
    val currentTool: StateFlow<AnnotationTool> = _currentTool.asStateFlow()

    // Appearance state
    private val _currentColor = MutableStateFlow(Color.Black)
    val currentColor: StateFlow<Color> = _currentColor.asStateFlow()

    private val _currentStrokeWidth = MutableStateFlow(3f)
    val currentStrokeWidth: StateFlow<Float> = _currentStrokeWidth.asStateFlow()

    private val _currentOpacity = MutableStateFlow(1.0f)
    val currentOpacity: StateFlow<Float> = _currentOpacity.asStateFlow()

    private val _currentPenType = MutableStateFlow(StrokeAnnotation.PenType.INK)
    val currentPenType: StateFlow<StrokeAnnotation.PenType> = _currentPenType.asStateFlow()

    // Selection state
    private val _selectedAnnotation = MutableStateFlow<Annotation?>(null)
    val selectedAnnotation: StateFlow<Annotation?> = _selectedAnnotation.asStateFlow()

    private val _selectedAnnotations = MutableStateFlow<List<Annotation>>(emptyList())
    val selectedAnnotations: StateFlow<List<Annotation>> = _selectedAnnotations.asStateFlow()

    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode: StateFlow<Boolean> = _isMultiSelectMode.asStateFlow()

    // Document state
    private val _currentDocumentId = MutableStateFlow<String?>(null)
    val currentDocumentId: StateFlow<String?> = _currentDocumentId.asStateFlow()

    private val _currentDocumentPath = MutableStateFlow<String>("")

    // UI state
    private val _showAnnotationList = MutableStateFlow(false)
    val showAnnotationList: StateFlow<Boolean> = _showAnnotationList.asStateFlow()

    private val _showLayerPanel = MutableStateFlow(false)
    val showLayerPanel: StateFlow<Boolean> = _showLayerPanel.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Exposed managers
    val layers = layerManager.layers
    val activeLayerId = layerManager.activeLayerId
    val canUndo = historyManager.canUndo
    val canRedo = historyManager.canRedo
    val undoDescription = historyManager.undoDescription
    val redoDescription = historyManager.redoDescription

    private val selectionTool = SelectionTool()

    // ==================== Initialization ====================

    fun initializeDocument(documentId: String, documentPath: String = "") {
        _currentDocumentId.value = documentId
        _currentDocumentPath.value = documentPath
        viewModelScope.launch {
            _isLoading.value = true
            try {
                layerManager.createLayer("Default")
                repository.getAnnotationsForDocument(documentId).collect { annotations ->
                    annotations.forEach { annotation ->
                        layerManager.getActiveLayer()?.addAnnotation(annotation)
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load annotations: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ==================== Tool Management ====================

    fun setTool(tool: AnnotationTool) {
        _currentTool.update { tool }
        if (tool != AnnotationTool.SELECTOR && tool != AnnotationTool.LASSO) {
            selectionTool.deselectAll()
            _selectedAnnotation.update { null }
            _selectedAnnotations.update { emptyList() }
        }
    }

    fun setColor(color: Color) {
        _currentColor.update { color }
        // Update selected annotations' color immediately
        if (selectionTool.hasSelection) {
            changeSelectedColor(color)
        }
    }

    fun setStrokeWidth(width: Float) {
        _currentStrokeWidth.update { width }
        if (selectionTool.hasSelection) {
            changeSelectedStrokeWidth(width)
        }
    }

    fun setOpacity(opacity: Float) {
        _currentOpacity.update { opacity }
        if (selectionTool.hasSelection) {
            changeSelectedOpacity(opacity)
        }
    }

    fun setPenType(penType: StrokeAnnotation.PenType) {
        _currentPenType.update { penType }
    }

    // ==================== Annotation Creation ====================

    fun createAnnotation(annotation: Annotation) {
        historyManager.addAnnotation(annotation)
        _selectedAnnotation.update { null }
        saveAnnotations()
    }

    fun createStrokeAnnotation(pageIndex: Int, points: List<PointF>) {
        val colorInt = android.graphics.Color.argb(
            (_currentOpacity.value * 255).toInt(),
            (_currentColor.value.red * 255).toInt(),
            (_currentColor.value.green * 255).toInt(),
            (_currentColor.value.blue * 255).toInt()
        )
        val annotation = StrokeAnnotation(
            pageIndex = pageIndex,
            points = points,
            strokeWidth = _currentStrokeWidth.value,
            penType = _currentPenType.value,
            color = colorInt,
            opacity = _currentOpacity.value
        )
        createAnnotation(annotation)
    }

    fun createShapeAnnotation(pageIndex: Int, shapeType: ShapeAnnotation.ShapeType, rect: RectF) {
        val colorInt = android.graphics.Color.argb(
            (_currentOpacity.value * 255).toInt(),
            (_currentColor.value.red * 255).toInt(),
            (_currentColor.value.green * 255).toInt(),
            (_currentColor.value.blue * 255).toInt()
        )
        val annotation = ShapeAnnotation(
            pageIndex = pageIndex,
            shapeType = shapeType,
            rect = rect,
            strokeWidth = _currentStrokeWidth.value,
            color = colorInt,
            opacity = _currentOpacity.value
        )
        createAnnotation(annotation)
    }

    fun createTextAnnotation(pageIndex: Int, textType: TextAnnotation.TextType, rect: RectF, text: String) {
        val colorInt = android.graphics.Color.argb(
            255,
            (_currentColor.value.red * 255).toInt(),
            (_currentColor.value.green * 255).toInt(),
            (_currentColor.value.blue * 255).toInt()
        )
        val annotation = TextAnnotation(
            pageIndex = pageIndex,
            textType = textType,
            text = text,
            rect = rect,
            color = colorInt,
            fontSize = _currentStrokeWidth.value * 3 // Scale font with stroke width
        )
        createAnnotation(annotation)
    }

    fun createHighlightAnnotation(pageIndex: Int, highlightType: HighlightAnnotation.HighlightType, rects: List<RectF>) {
        val colorInt = android.graphics.Color.argb(
            128,
            (_currentColor.value.red * 255).toInt(),
            (_currentColor.value.green * 255).toInt(),
            (_currentColor.value.blue * 255).toInt()
        )
        val annotation = HighlightAnnotation(
            pageIndex = pageIndex,
            highlightType = highlightType,
            rects = rects,
            color = colorInt,
            opacity = 0.3f
        )
        createAnnotation(annotation)
    }

    fun createStampAnnotation(pageIndex: Int, stampType: StampAnnotation.StampType, rect: RectF, customText: String = "") {
        val annotation = StampAnnotation(
            pageIndex = pageIndex,
            stampType = stampType,
            rect = rect,
            customText = customText
        )
        createAnnotation(annotation)
    }

    // ==================== Selection ====================

    fun selectAnnotation(annotation: Annotation, additive: Boolean = false) {
        selectionTool.select(annotation, additive)
        _selectedAnnotation.update { annotation }
        _selectedAnnotations.update { selectionTool.selectedAnnotations }
    }

    fun selectByLasso(lasso: LassoAnnotation) {
        val allAnnotations = layerManager.getAnnotationsForPage(lasso.pageIndex)
        selectionTool.selectByLasso(lasso, allAnnotations)
        _selectedAnnotations.update { selectionTool.selectedAnnotations }
        _selectedAnnotation.update { selectionTool.selectedAnnotations.firstOrNull() }
    }

    fun deselectAll() {
        selectionTool.deselectAll()
        _selectedAnnotation.update { null }
        _selectedAnnotations.update { emptyList() }
    }

    fun toggleMultiSelectMode() {
        _isMultiSelectMode.update { !it }
        if (!_isMultiSelectMode.value) {
            deselectAll()
        }
    }

    // ==================== Modification ====================

    fun deleteSelected() {
        val selected = selectionTool.deleteSelection()
        selected.forEach { historyManager.deleteAnnotation(it) }
        _selectedAnnotation.update { null }
        _selectedAnnotations.update { emptyList() }
        saveAnnotations()
    }

    fun deleteAnnotation(annotation: Annotation) {
        historyManager.deleteAnnotation(annotation)
        if (_selectedAnnotation.value?.id == annotation.id) {
            _selectedAnnotation.update { null }
        }
        saveAnnotations()
    }

    fun changeSelectedColor(color: Color) {
        val newColor = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
        val updates = selectionTool.selectedAnnotations.map { old ->
            val updated = transformer.changeColor(old, newColor)
            historyManager.modifyAnnotation(old, updated)
            updated
        }
        _selectedAnnotations.update { updates }
        saveAnnotations()
    }

    fun changeSelectedOpacity(opacity: Float) {
        val updates = selectionTool.selectedAnnotations.map { old ->
            val updated = transformer.changeOpacity(old, opacity)
            historyManager.modifyAnnotation(old, updated)
            updated
        }
        _selectedAnnotations.update { updates }
        saveAnnotations()
    }

    fun changeSelectedStrokeWidth(width: Float) {
        val updates = selectionTool.selectedAnnotations.map { old ->
            val updated = transformer.changeStrokeWidth(old, width)
            historyManager.modifyAnnotation(old, updated)
            updated
        }
        _selectedAnnotations.update { updates }
        saveAnnotations()
    }

    fun moveSelected(dx: Float, dy: Float) {
        val moved = selectionTool.moveSelection(dx, dy)
        val selected = selectionTool.selectedAnnotations
        selected.zip(moved).forEach { (old, new) ->
            historyManager.modifyAnnotation(old, new)
        }
        _selectedAnnotations.update { moved }
        saveAnnotations()
    }

    fun scaleSelected(factor: Float, pivotX: Float, pivotY: Float) {
        val scaled = selectionTool.scaleSelection(factor, pivotX, pivotY)
        val selected = selectionTool.selectedAnnotations
        selected.zip(scaled).forEach { (old, new) ->
            historyManager.modifyAnnotation(old, new)
        }
        _selectedAnnotations.update { scaled }
        saveAnnotations()
    }

    fun rotateSelected(degrees: Float, pivotX: Float, pivotY: Float) {
        val rotated = selectionTool.rotateSelection(degrees, pivotX, pivotY)
        val selected = selectionTool.selectedAnnotations
        selected.zip(rotated).forEach { (old, new) ->
            historyManager.modifyAnnotation(old, new)
        }
        _selectedAnnotations.update { rotated }
        saveAnnotations()
    }

    fun bringToFront() {
        val selected = selectionTool.selectedAnnotations
        selected.forEach { annotation ->
            layerManager.getActiveLayer()?.bringToFront(annotation.id)
        }
        saveAnnotations()
    }

    fun sendToBack() {
        val selected = selectionTool.selectedAnnotations
        selected.forEach { annotation ->
            layerManager.getActiveLayer()?.sendToBack(annotation.id)
        }
        saveAnnotations()
    }

    // ==================== Undo/Redo ====================

    fun undo() {
        historyManager.undo()
        saveAnnotations()
    }

    fun redo() {
        historyManager.redo()
        saveAnnotations()
    }

    // ==================== Layer Management ====================

    fun createLayer(name: String) {
        layerManager.createLayer(name)
    }

    fun deleteLayer(layerId: String) {
        layerManager.deleteLayer(layerId)
        saveAnnotations()
    }

    fun setActiveLayer(layerId: String) {
        layerManager.setActiveLayer(layerId)
    }

    fun setLayerVisibility(layerId: String, visible: Boolean) {
        layerManager.setLayerVisibility(layerId, visible)
    }

    fun setLayerOpacity(layerId: String, opacity: Float) {
        layerManager.setLayerOpacity(layerId, opacity)
    }

    fun toggleLayerPanel() {
        _showLayerPanel.update { !it }
    }

    // ==================== Annotation List ====================

    fun toggleAnnotationList() {
        _showAnnotationList.update { !it }
    }

    fun getAllAnnotations(): List<Annotation> {
        return layerManager.getAllAnnotations()
    }

    fun getAnnotationsForPage(pageIndex: Int): List<Annotation> {
        return layerManager.getAnnotationsForPage(pageIndex)
    }

    // ==================== Export/Import ====================

    fun exportAnnotations(outputFile: java.io.File) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val docId = _currentDocumentId.value ?: return@launch
                repository.exportToJson(docId, outputFile)
            } catch (e: Exception) {
                _errorMessage.value = "Export failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun importAnnotations(jsonFile: java.io.File) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val docId = _currentDocumentId.value ?: return@launch
                val imported = repository.importFromJson(docId, _currentDocumentPath.value, jsonFile)
                imported.forEach { annotation ->
                    layerManager.getActiveLayer()?.addAnnotation(annotation)
                }
                saveAnnotations()
            } catch (e: Exception) {
                _errorMessage.value = "Import failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ==================== Persistence ====================

    private fun saveAnnotations() {
        val docId = _currentDocumentId.value ?: return
        val docPath = _currentDocumentPath.value
        viewModelScope.launch {
            try {
                val allAnnotations = layerManager.getAllAnnotations()
                repository.saveAnnotations(docId, docPath, allAnnotations)
            } catch (e: Exception) {
                _errorMessage.value = "Save failed: ${e.message}"
            }
        }
    }

    fun forceSave() {
        historyManager.forceSave()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    // ==================== Annotation Tool Enum ====================

    enum class AnnotationTool {
        PEN,
        CALLIGRAPHY,
        MARKER,
        PENCIL,
        ERASER,
        HIGHLIGHT,
        UNDERLINE,
        STRIKEOUT,
        SQUIGGLY,
        TEXT,
        STICKY_NOTE,
        TEXTBOX,
        FREE_TEXT,
        RECTANGLE,
        CIRCLE,
        LINE,
        ARROW,
        POLYGON,
        CLOUD,
        STAMP,
        DATE_STAMP,
        SIGNATURE,
        SELECTOR,
        LASSO
    }
}
