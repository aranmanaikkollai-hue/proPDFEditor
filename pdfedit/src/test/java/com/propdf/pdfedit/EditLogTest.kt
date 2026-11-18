package com.propdf.pdfedit

import com.propdf.pdfedit.ops.DeleteImage
import com.propdf.pdfedit.ops.EditLog
import com.propdf.pdfedit.ops.ImageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EditLogTest {
    private fun op(id: String) = DeleteImage(id, 0, ImageRef(0, 0, "Im0"))

    @Test
    fun emptyLogHasNothingToUndoOrRedo() {
        val log = EditLog()
        assertFalse(log.canUndo); assertFalse(log.canRedo)
        assertNull(log.undo()); assertNull(log.redo())
        assertTrue(log.active.isEmpty())
    }

    @Test
    fun undoAndRedoMoveTheCursor() {
        val log = EditLog()
        val a = op("a"); val b = op("b")
        log.apply(a); log.apply(b)
        assertEquals(listOf("a", "b"), log.active.map { it.id })
        assertSame(b, log.undo())
        assertEquals(listOf("a"), log.active.map { it.id })
        assertTrue(log.canRedo)
        assertSame(b, log.redo())
        assertEquals(listOf("a", "b"), log.active.map { it.id })
        assertFalse(log.canRedo)
    }

    @Test
    fun applyingAfterUndoDropsTheRedoTail() {
        val log = EditLog()
        log.apply(op("a")); log.apply(op("b")); log.apply(op("c"))
        log.undo(); log.undo()
        log.apply(op("d"))
        assertEquals(listOf("a", "d"), log.active.map { it.id })
        assertFalse(log.canRedo)
    }

    @Test
    fun undoEverythingThenRedoEverything() {
        val log = EditLog()
        for (id in listOf("a", "b", "c")) log.apply(op(id))
        while (log.canUndo) log.undo()
        assertTrue(log.active.isEmpty())
        while (log.canRedo) log.redo()
        assertEquals(listOf("a", "b", "c"), log.active.map { it.id })
    }

    @Test
    fun activeIsASnapshotNotALiveView() {
        val log = EditLog()
        log.apply(op("a"))
        val snapshot = log.active
        log.apply(op("b"))
        assertEquals(1, snapshot.size)
    }
}
