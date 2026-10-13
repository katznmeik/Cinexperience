package io.github.katznmeik.cinexperience.feature.seatmap.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoHistoryTest {

    @Test
    fun undoAndRedoWalkThroughRecordedStates() {
        val history = UndoHistory<String>()
        assertFalse(history.canUndo)
        history.record("a")
        history.record("b")
        assertEquals("b", history.undo(current = "c"))
        assertEquals("a", history.undo(current = "b"))
        assertNull(history.undo(current = "a"))
        assertTrue(history.canRedo)
        assertEquals("b", history.redo(current = "a"))
        assertEquals("c", history.redo(current = "b"))
        assertNull(history.redo(current = "c"))
    }

    @Test
    fun aNewEditClearsRedo() {
        val history = UndoHistory<String>()
        history.record("a")
        history.undo("b")
        history.record("a")
        assertFalse(history.canRedo)
    }

    @Test
    fun editsWithTheSameKeyFormOneStep() {
        val history = UndoHistory<String>()
        history.record("", coalesceKey = "label")
        history.record("A", coalesceKey = "label")
        history.record("AB", coalesceKey = "label")
        assertEquals("", history.undo("ABC"))
        assertFalse(history.canUndo)

        history.record("x", coalesceKey = "label")
        history.breakCoalescing()
        history.record("y", coalesceKey = "label")
        assertEquals("y", history.undo("z"))
        assertEquals("x", history.undo("y"))
    }

    @Test
    fun theOldestStepsAreDroppedAtTheLimit() {
        val history = UndoHistory<Int>(limit = 3)
        (1..5).forEach { history.record(it) }
        assertEquals(5, history.undo(6))
        assertEquals(4, history.undo(5))
        assertEquals(3, history.undo(4))
        assertNull(history.undo(3))
    }
}
