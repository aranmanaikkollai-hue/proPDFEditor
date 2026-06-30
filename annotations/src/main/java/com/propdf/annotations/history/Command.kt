package com.propdf.annotations.history

/**
 * Command pattern interface for undo/redo operations.
 * All annotation mutations go through commands for history tracking.
 */
interface Command {
    /**
     * Execute the command.
     */
    fun execute()

    /**
     * Undo the command.
     */
    fun undo()

    /**
     * Get human-readable description for UI.
     */
    fun getDescription(): String

    /**
     * Get the annotation IDs affected by this command.
     */
    fun getAffectedIds(): List<String> = emptyList()
}
