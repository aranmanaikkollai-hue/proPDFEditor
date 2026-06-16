// annotations/src/main/java/com/propdf/annotations/history/Command.kt
package com.propdf.annotations.history

/**
 * Command pattern interface for undo/redo operations.
 */
interface Command {
    fun execute()
    fun undo()
    fun getDescription(): String
}
