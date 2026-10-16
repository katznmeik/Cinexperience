package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.labels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Columns −1" in the editor removes a row's start seat (spec §4.3 / §6.5): the
 * other seats keep their numbers, and Undo and painting the seat back restore it.
 */
class BlockStartColumnCountTest {

    /** Rollberg Saal 5 row 5 style: `.......SSSSS`, right to left from 2 (6 5 4 3 2). */
    private val stored = SeatLayout.fromPatterns(".......SSSSS", firstSeatId = 1)
        .updateRow(0) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT, blockStartNumbers = listOf(2)) }
    private val hall = Hall(id = 1, cinemaId = 1, name = "Saal 5", layout = stored, screenWidth = 12f, screenDistance = 1.5f)

    private fun LayoutDraft.texts(): List<String> = layout.rows[0].seatIds.map { layout.labels().labelOf(it)!!.seat }

    @Test
    fun removingTheLastColumnKeepsTheOtherNumbersAndUndoRestoresIt() {
        val draft = LayoutDraft.start(hall)
        val history = UndoHistory<LayoutDraft>()
        assertEquals(listOf("6", "5", "4", "3", "2"), draft.texts())

        history.record(draft)
        val narrower = draft.withColumnCount(11)
        assertEquals(listOf("6", "5", "4", "3"), narrower.texts())
        assertEquals(listOf(3), narrower.layout.rows[0].blockStartNumbers)

        val back = history.undo(narrower)!!
        assertEquals(stored.rows, back.layout.rows)
        assertTrue(back.hasSameContentAs(draft))
    }

    @Test
    fun theSeatPaintedAwayAndBackGetsItsStartBack() {
        val draft = LayoutDraft.start(hall)
        val away = draft.paint(0, 11, CellType.GAP)
        assertEquals(listOf("6", "5", "4", "3"), away.texts())
        val back = away.paint(0, 11, CellType.SEAT)
        assertEquals(listOf("6", "5", "4", "3", "2"), back.texts())
        assertEquals(stored.rows, back.layout.rows)
        assertTrue(back.hasSameContentAs(draft))
    }
}
