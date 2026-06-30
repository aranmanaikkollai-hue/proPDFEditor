package com.propdf.annotations.history

import java.util.ArrayDeque

/**
 * Memory-efficient undo/redo stack with size limits.
 * Supports batch commands for compound operations.
 */
class CommandHistory(
    private val maxSize: Int = 100
) {
    private val undoStack = ArrayDeque<Command>(maxSize)
    private val redoStack = ArrayDeque<Command>(maxSize)

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Execute a single command.
     */
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

    /**
     * Execute a batch of commands as a single undoable action.
     */
    fun executeBatch(commands: List<Command>) {
        if (commands.isEmpty()) return
        val batch = BatchCommand(commands)
        execute(batch)
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

    /**
     * Batch multiple commands into a single undoable unit.
     */
    private class BatchCommand(
        private val commands: List<Command>
    ) : Command {
        override fun execute() {
            commands.forEach { it.execute() }
        }

        override fun undo() {
            commands.asReversed().forEach { it.undo() }
        }

        override fun getDescription(): String {
            val first = commands.firstOrNull()?.getDescription() ?: "Batch operation"
            return if (commands.size > 1) "$first (+${commands.size - 1})" else first
        }

        override fun getAffectedIds(): List<String> {
            return commands.flatMap { it.getAffectedIds() }.distinct()
        }
    }
}
