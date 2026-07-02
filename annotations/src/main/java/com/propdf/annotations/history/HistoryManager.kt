package com.propdf.annotations.history

import com.propdf.annotations.layers.LayerManager
import com.propdf.annotations.model.Annotation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

/**
 * Orchestrates command history with autosave integration.
 * Thread-safe: All state updates happen on main thread via StateFlow.
 */
class HistoryManager(
    private val layerManager: LayerManager,
    private val maxHistorySize: Int = 100,
    private val autoSaveDelayMs: Long = 2000,
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

    private var autoSaveJob: Job? = null

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

    fun deleteAnnotations(annotations: List<Annotation>) {
        val commands = annotations.map { DeleteAnnotationCommand(layerManager, it) }
        commandHistory.executeBatch(commands)
        updateState()
        scheduleAutoSave()
    }

    /**
     * Replaces [toRemove] with [toAdd] as a single undoable operation.
     * Used by the eraser: erasing a stroke deletes the original and adds back
     * zero or more split segments, all as one undo step.
     */
    fun replaceAnnotations(toRemove: List<Annotation>, toAdd: List<Annotation>) {
        if (toRemove.isEmpty() && toAdd.isEmpty()) return
        val commands: List<Command> =
            toRemove.map { DeleteAnnotationCommand(layerManager, it) } +
                toAdd.map { AddAnnotationCommand(layerManager, it) }
        commandHistory.executeBatch(commands)
        updateState()
        scheduleAutoSave()
    }

    fun modifyAnnotation(oldAnnotation: Annotation, newAnnotation: Annotation) {
        val command = ModifyAnnotationCommand(layerManager, oldAnnotation, newAnnotation)
        commandHistory.execute(command)
        updateState()
        scheduleAutoSave()
    }

    fun modifyAnnotations(updates: List<Pair<Annotation, Annotation>>) {
        val commands = updates.map { (old, new) ->
            ModifyAnnotationCommand(layerManager, old, new)
        }
        commandHistory.executeBatch(commands)
        updateState()
        scheduleAutoSave()
    }

    fun moveAnnotations(annotations: List<Annotation>, dx: Float, dy: Float) {
        val command = MoveAnnotationCommand(layerManager, annotations, dx, dy)
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
        autoSaveJob = CoroutineScope(Dispatchers.IO).launch {
            delay(autoSaveDelayMs)
            onPersist()
        }
    }

    fun forceSave() {
        autoSaveJob?.cancel()
        CoroutineScope(Dispatchers.IO).launch {
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
