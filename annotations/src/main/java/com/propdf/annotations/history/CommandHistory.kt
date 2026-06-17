package com.propdf.annotations.history

import java.util.ArrayDeque

/**
 * Memory-efficient undo/redo stack with size limits.
 */
class CommandHistory(
    private val maxSize: Int = 100
) {
    private val undoStack = ArrayDeque<Command>(maxSize)
    private val redoStack = ArrayDeque<Command>(maxSize)

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun execute(command: Command) {
        command.execute()
        undoStack.addLast(command)

        // Clear redo stack on new action
        redoStack.clear()

        // Trim to max size
        while (undoStack.size > maxSize) {
            undoStack.removeFirst()
        }
    }

    fun undo(): Boolean {
        if (!canUndo) return false
        val command = undoStack.removeLast()
        command.undo()
        redoStack.addLast(command)
        return true
    }

    fun redo(): Boolean {
        if (!canRedo) return false
        val command = redoStack.removeLast()
        command.execute()
        undoStack.addLast(command)
        return true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    fun getUndoDescription(): String? = undoStack.lastOrNull()?.getDescription()
    fun getRedoDescription(): String? = redoStack.lastOrNull()?.getDescription()

    fun getUndoCount(): Int = undoStack.size
    fun getRedoCount(): Int = redoStack.size
}
