package com.propdf.annotations.ui

import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.annotations.export.PdfAnnotationExporter
import com.propdf.annotations.history.HistoryManager
import com.propdf.annotations.layers.LayerManager
import com.propdf.annotations.model.*
import com.propdf.annotations.model.Annotation
import com.propdf.annotations.persistence.AnnotationRepository
import com.propdf.annotations.transform.AnnotationTransformer
import com.propdf.annotations.transform.SelectionTool
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
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
    private val transformer: AnnotationTransformer,
    private val pdfExporter: PdfAnnotationExporter
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
    val currentDocumentPath: StateFlow<String> = _currentDocumentPath.asStateFlow()

    // UI state
    private val _showAnnotationList = MutableStateFlow(false)
    val showAnnotationList: StateFlow<Boolean> = _showAnnotationList.asStateFlow()

    private val _showLayerPanel = MutableStateFlow(false)
    val showLayerPanel: StateFlow<Boolean> = _showLayerPanel.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // One-shot events for UI feedback (Snackbars, toasts), e.g. "Saved", "Exported to ..."
    private val _saveEvent = MutableSharedFlow<SaveEvent>(extraBufferCapacity = 4)
    val saveEvent: SharedFlow<SaveEvent> = _saveEvent.asSharedFlow()

    // Exposed managers
    val layers = layerManager.layers
    val activeLayerId = layerManager.activeLayerId
    val canUndo = historyManager.canUndo
    val canRedo = historyManager.canRedo
    val undoDescription = historyManager.undoDescription
    val redoDescription = historyManager.redoDescription

    private val selectionTool = SelectionTool()

    // ==================== Initialization ====================

    /**
     * Loads any previously saved annotations for this document into the active layer.
     *
     * This is a ONE-SHOT load (repository.getAnnotationsForDocument(...).first()), not an
     * ongoing subscription. Room's DAO query is a Flow that re-emits on every write to the
     * annotations table -- including the writes this ViewModel itself performs via
     * saveAnnotations(). Collecting it with `.collect { }` would re-run on every autosave and
     * re-add every already-loaded annotation into the layer again, duplicating them and never
     * completing (which also left `_isLoading` stuck at true forever).
     */
    fun initializeDocument(documentId: String, documentPath: String = "") {
        _currentDocumentId.value = documentId
        _currentDocumentPath.value = documentPath
        viewModelScope.launch {
            _isLoading.value = true
            try {
                layerManager.createLayer("Default")
                val existing = repository.getAnnotationsForDocument(documentId).first()
                existing.forEach { annotation ->
                    layerManager.getActiveLayer()?.addAnnotation(annotation)
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load annotations: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Updates the on-disk path for the current document (e.g. once a content:// URI has been
     * resolved to a local cache file). Needed for export/flatten/burn, which operate on a real
     * File, not a content URI.
     */
    fun setDocumentPath(path: String) {
        _currentDocumentPath.value = path
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

    /** Creates a POLYGON or CLOUD shape from a list of user-tapped vertices. */
    fun createPolygonAnnotation(pageIndex: Int, shapeType: ShapeAnnotation.ShapeType, vertices: List<PointF>) {
        if (vertices.size < 3) return
        val colorInt = android.graphics.Color.argb(
            (_currentOpacity.value * 255).toInt(),
            (_currentColor.value.red * 255).toInt(),
            (_currentColor.value.green * 255).toInt(),
            (_currentColor.value.blue * 255).toInt()
        )
        val bounds = RectF(
            vertices.minOf { it.x }, vertices.minOf { it.y },
            vertices.maxOf { it.x }, vertices.maxOf { it.y }
        )
        val annotation = ShapeAnnotation(
            pageIndex = pageIndex,
            shapeType = shapeType,
            rect = bounds,
            vertices = vertices,
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
            fontSize = _currentStrokeWidth.value * 3
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

    /** Updates the text content of an existing text annotation (used by the text-edit dialog). */
    fun updateTextAnnotationContent(annotationId: String, text: String) {
        val existing = layerManager.getAllAnnotations()
            .filterIsInstance<TextAnnotation>()
            .find { it.id == annotationId } ?: return
        val updated = existing.copy(text = text, modifiedAt = System.currentTimeMillis())
        historyManager.modifyAnnotation(existing, updated)
        if (_selectedAnnotation.value?.id == annotationId) {
            _selectedAnnotation.update { updated }
        }
        saveAnnotations()
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

    /** Combined bounding box (in PDF point space) of the current selection, or null if empty. */
    fun getSelectionBounds(): RectF? = selectionTool.selectionBounds

    /** Which resize handle (0-7, or -1) is at the given PDF-space point, for the current selection. */
    fun getSelectionHandleAt(x: Float, y: Float, tolerance: Float): Int =
        selectionTool.getHandleAt(x, y, tolerance)

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
        selectionTool.replaceSelection(updates)
        _selectedAnnotations.update { updates }
        saveAnnotations()
    }

    fun changeSelectedOpacity(opacity: Float) {
        val updates = selectionTool.selectedAnnotations.map { old ->
            val updated = transformer.changeOpacity(old, opacity)
            historyManager.modifyAnnotation(old, updated)
            updated
        }
        selectionTool.replaceSelection(updates)
        _selectedAnnotations.update { updates }
        saveAnnotations()
    }

    fun changeSelectedStrokeWidth(width: Float) {
        val updates = selectionTool.selectedAnnotations.map { old ->
            val updated = transformer.changeStrokeWidth(old, width)
            historyManager.modifyAnnotation(old, updated)
            updated
        }
        selectionTool.replaceSelection(updates)
        _selectedAnnotations.update { updates }
        saveAnnotations()
    }

    fun moveSelected(dx: Float, dy: Float) {
        val moved = selectionTool.moveSelection(dx, dy)
        val selected = selectionTool.selectedAnnotations
        selected.zip(moved).forEach { (old, new) ->
            historyManager.modifyAnnotation(old, new)
        }
        selectionTool.replaceSelection(moved)
        _selectedAnnotations.update { moved }
        saveAnnotations()
    }

    fun scaleSelected(factor: Float, pivotX: Float, pivotY: Float) {
        val scaled = selectionTool.scaleSelection(factor, pivotX, pivotY)
        val selected = selectionTool.selectedAnnotations
        selected.zip(scaled).forEach { (old, new) ->
            historyManager.modifyAnnotation(old, new)
        }
        selectionTool.replaceSelection(scaled)
        _selectedAnnotations.update { scaled }
        saveAnnotations()
    }

    fun rotateSelected(degrees: Float, pivotX: Float, pivotY: Float) {
        val rotated = selectionTool.rotateSelection(degrees, pivotX, pivotY)
        val selected = selectionTool.selectedAnnotations
        selected.zip(rotated).forEach { (old, new) ->
            historyManager.modifyAnnotation(old, new)
        }
        selectionTool.replaceSelection(rotated)
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

    /**
     * Erases ink along [eraserPathPdf] (a PDF-space path the user dragged with the eraser tool).
     *
     * This does NOT paint a "clear" stroke (PorterDuff CLEAR has no effect without an explicit
     * offscreen compositing layer, which is why that approach never visibly erased anything).
     * Instead it finds every freehand stroke on the page whose points fall within
     * [eraserRadiusPdf] of the eraser path and splits each one at the erased points, keeping
     * the un-erased segments as new strokes and dropping the rest. Shapes, text, highlights,
     * and stamps are left alone (as in most PDF editors, the eraser only affects ink).
     */
    fun eraseAtPath(pageIndex: Int, eraserPathPdf: List<PointF>, eraserRadiusPdf: Float) {
        if (eraserPathPdf.isEmpty()) return
        val strokes = layerManager.getAnnotationsForPage(pageIndex)
            .filterIsInstance<StrokeAnnotation>()
            .filter { it.penType != StrokeAnnotation.PenType.ERASER }

        val toRemove = mutableListOf<Annotation>()
        val toAdd = mutableListOf<Annotation>()

        strokes.forEach { stroke ->
            val anyPointErased = stroke.points.any { point ->
                eraserPathPdf.any { ep ->
                    val dx = point.x - ep.x
                    val dy = point.y - ep.y
                    (dx * dx + dy * dy) <= eraserRadiusPdf * eraserRadiusPdf
                }
            }
            if (!anyPointErased) return@forEach

            toRemove.add(stroke)
            splitPointsAroundEraser(stroke.points, eraserPathPdf, eraserRadiusPdf).forEach { segment ->
                if (segment.size >= 2) {
                    toAdd.add(
                        stroke.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            points = segment,
                            modifiedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        if (toRemove.isNotEmpty() || toAdd.isNotEmpty()) {
            historyManager.replaceAnnotations(toRemove, toAdd)
            saveAnnotations()
        }
    }

    private fun splitPointsAroundEraser(
        points: List<PointF>,
        eraserPath: List<PointF>,
        radius: Float
    ): List<List<PointF>> {
        val result = mutableListOf<List<PointF>>()
        var current = mutableListOf<PointF>()
        points.forEach { point ->
            val erased = eraserPath.any { ep ->
                val dx = point.x - ep.x
                val dy = point.y - ep.y
                (dx * dx + dy * dy) <= radius * radius
            }
            if (erased) {
                if (current.size >= 2) result.add(current)
                current = mutableListOf()
            } else {
                current.add(point)
            }
        }
        if (current.size >= 2) result.add(current)
        return result
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

    // ==================== Export/Import (annotation data as JSON) ====================

    fun exportAnnotations(outputFile: File) {
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val docId = _currentDocumentId.value
                if (docId == null) {
                    _saveEvent.emit(SaveEvent.Failure("No document loaded yet"))
                    return@launch
                }
                val success = repository.exportToJson(docId, outputFile)
                _saveEvent.emit(
                    if (success) SaveEvent.ExportSuccess(outputFile.absolutePath)
                    else SaveEvent.Failure("Export failed")
                )
            } catch (e: Exception) {
                _errorMessage.value = "Export failed: ${e.message}"
                _saveEvent.emit(SaveEvent.Failure(e.message ?: "Export failed"))
            } finally {
                _isExporting.value = false
            }
        }
    }

    fun importAnnotations(jsonFile: File) {
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val docId = _currentDocumentId.value
                if (docId == null) {
                    _saveEvent.emit(SaveEvent.Failure("No document loaded yet"))
                    return@launch
                }
                val imported = repository.importFromJson(docId, _currentDocumentPath.value, jsonFile)
                imported.forEach { annotation ->
                    layerManager.getActiveLayer()?.addAnnotation(annotation)
                }
                saveAnnotations()
                _saveEvent.emit(SaveEvent.Success(imported.size))
            } catch (e: Exception) {
                _errorMessage.value = "Import failed: ${e.message}"
                _saveEvent.emit(SaveEvent.Failure(e.message ?: "Import failed"))
            } finally {
                _isExporting.value = false
            }
        }
    }

    // ==================== Flatten / Burn (produce a new PDF) ====================

    /**
     * Bakes annotations into the PDF as native, non-editable PDF graphics
     * (annotations remain vector, page content is still searchable/selectable).
     */
    fun flattenAnnotations(outputFile: File) {
        viewModelScope.launch {
            val inputPath = _currentDocumentPath.value
            val docId = _currentDocumentId.value
            if (inputPath.isBlank() || docId == null) {
                _saveEvent.emit(SaveEvent.Failure("Document is still loading, please wait"))
                return@launch
            }
            _isExporting.value = true
            try {
                val success = pdfExporter.flattenAnnotations(File(inputPath), outputFile, docId)
                _saveEvent.emit(
                    if (success) SaveEvent.ExportSuccess(outputFile.absolutePath)
                    else SaveEvent.Failure("Flatten failed")
                )
            } catch (e: Exception) {
                _saveEvent.emit(SaveEvent.Failure(e.message ?: "Flatten failed"))
            } finally {
                _isExporting.value = false
            }
        }
    }

    /**
     * Rasterizes each page (with annotations baked in) into a flat image-based PDF.
     * Use for final, guaranteed non-editable output.
     */
    fun burnAnnotationsIntoPdf(outputFile: File, dpi: Int = 300) {
        viewModelScope.launch {
            val inputPath = _currentDocumentPath.value
            val docId = _currentDocumentId.value
            if (inputPath.isBlank() || docId == null) {
                _saveEvent.emit(SaveEvent.Failure("Document is still loading, please wait"))
                return@launch
            }
            _isExporting.value = true
            try {
                val success = pdfExporter.burnAnnotationsIntoPdf(File(inputPath), outputFile, docId, dpi)
                _saveEvent.emit(
                    if (success) SaveEvent.ExportSuccess(outputFile.absolutePath)
                    else SaveEvent.Failure("Burn failed")
                )
            } catch (e: Exception) {
                _saveEvent.emit(SaveEvent.Failure(e.message ?: "Burn failed"))
            } finally {
                _isExporting.value = false
            }
        }
    }

    // ==================== Persistence ====================

    /**
     * Persists the current in-memory annotation state to the database.
     * Called automatically (silently) after every mutation, and explicitly
     * (with UI feedback) when the user taps Save.
     */
    private fun saveAnnotations(notifyUser: Boolean = false) {
        val docId = _currentDocumentId.value ?: run {
            if (notifyUser) {
                viewModelScope.launch { _saveEvent.emit(SaveEvent.Failure("No document loaded yet")) }
            }
            return
        }
        val docPath = _currentDocumentPath.value
        viewModelScope.launch {
            try {
                val allAnnotations = layerManager.getAllAnnotations()
                repository.saveAnnotations(docId, docPath, allAnnotations)
                if (notifyUser) _saveEvent.emit(SaveEvent.Success(allAnnotations.size))
            } catch (e: Exception) {
                _errorMessage.value = "Save failed: ${e.message}"
                if (notifyUser) _saveEvent.emit(SaveEvent.Failure(e.message ?: "Save failed"))
            }
        }
    }

    /** Explicit, user-triggered save with UI feedback (Save button). */
    fun forceSave() {
        saveAnnotations(notifyUser = true)
    }

    fun clearError() {
        _errorMessage.value = null
    }

    // ==================== Save/Export Events ====================

    sealed class SaveEvent {
        data class Success(val annotationCount: Int) : SaveEvent()
        data class ExportSuccess(val path: String) : SaveEvent()
        data class Failure(val message: String) : SaveEvent()
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
