// annotations/src/main/java/com/propdf/annotations/history/HistoryManager.kt
package com.propdf.annotations.history

import com.propdf.annotations.layers.LayerManager
import com.propdf.annotations.model.Annotation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Orchestrates command history with autosave integration.
 */
class HistoryManager(
    private val layerManager: LayerManager,
    private val maxHistorySize: Int = 100,
    private val onPersist: suspend () -> Unit = {}
) {
    private val commandHistory = CommandHistory(maxHistorySize)

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val _undoDescription = MutableStateFlow<String?>(null)
    val undoDescription: StateFlow<String?> = _undoDescription.asStateFlow()

    private val _redoDescription = MutableStateFlow<String?>(null)
    val redoDescription: StateFlow<String?> = _redoDescription.asStateFlow()

    private var autoSaveJob: kotlinx.coroutines.Job? = null

    fun addAnnotation(annotation: Annotation) {
        val command = AddAnnotationCommand(layerManager, annotation)
        commandHistory.execute(command)
        updateState()
        scheduleAutoSave()
    }

    fun deleteAnnotation(annotation: Annotation) {
        val command = DeleteAnnotationCommand(layerManager, annotation)
        commandHistory.execute(command)
        updateState()
        scheduleAutoSave()
    }

    fun modifyAnnotation(oldAnnotation: Annotation, newAnnotation: Annotation) {
        val command = ModifyAnnotationCommand(layerManager, oldAnnotation, newAnnotation)
        commandHistory.execute(command)
        updateState()
        scheduleAutoSave()
    }

    fun undo(): Boolean {
        val result = commandHistory.undo()
        updateState()
        scheduleAutoSave()
        return result
    }

    fun redo(): Boolean {
        val result = commandHistory.redo()
        updateState()
        scheduleAutoSave()
        return result
    }

    fun clear() {
        commandHistory.clear()
        updateState()
    }

    private fun updateState() {
        _canUndo.update { commandHistory.canUndo }
        _canRedo.update { commandHistory.canRedo }
        _undoDescription.update { commandHistory.getUndoDescription() }
        _redoDescription.update { commandHistory.getRedoDescription() }
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(3000)  // 3 second debounce
            onPersist()
        }
    }

    fun getHistoryStats(): HistoryStats = HistoryStats(
        undoCount = commandHistory.getUndoCount(),
        redoCount = commandHistory.getRedoCount(),
        maxSize = maxHistorySize
    )

    data class HistoryStats(
        val undoCount: Int,
        val redoCount: Int,
        val maxSize: Int
    )
}
