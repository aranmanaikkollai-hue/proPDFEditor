package com.propdf.pdfedit.ops

/**
 * Ordered list of operations plus a cursor. This is the whole undo/redo mechanism: undo and redo only move the
 * cursor; the output is always produced by replaying [active] on the untouched base file, so no operation ever
 * needs an inverse.
 */
class EditLog {
    private val operations = ArrayList<EditOperation>()
    private var cursor = 0

    /** Operations that are currently applied, in order. */
    val active: List<EditOperation> get() = operations.subList(0, cursor).toList()

    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < operations.size

    /** Adds an operation after the cursor; anything that was undone and not redone is discarded. */
    fun apply(op: EditOperation) {
        while (operations.size > cursor) operations.removeAt(operations.size - 1)
        operations.add(op)
        cursor = operations.size
    }

    /** Un-applies the latest operation and returns it, or null when there is nothing to undo. */
    fun undo(): EditOperation? = if (canUndo) operations[--cursor] else null

    /** Re-applies the most recently undone operation and returns it, or null when there is nothing to redo. */
    fun redo(): EditOperation? = if (canRedo) operations[cursor++] else null
}
