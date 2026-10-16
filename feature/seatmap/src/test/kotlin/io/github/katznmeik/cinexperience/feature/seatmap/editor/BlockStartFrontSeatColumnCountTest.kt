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
 * "Columns +1" in the editor on a right-to-left row whose start seat is at the
 * right edge (spec §4.3 / §6.5): the new seat comes first in numbering order and
 * takes the number before the start, so the existing seats keep their numbers.
 * Undo restores the row.
 */
class BlockStartFrontSeatColumnCountTest {

    /** Kant Kino style: `...SSSSSSSS`, right to left from 3 (10 9 … 3), the 3 at the right edge. */
    private val stored = SeatLayout.fromPatterns("...SSSSSSSS", firstSeatId = 1)
        .updateRow(0) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT, blockStartNumbers = listOf(3)) }
    private val hall = Hall(id = 1, cinemaId = 1, name = "Saal 4", layout = stored, screenWidth = 11f, screenDistance = 1.5f)

    private fun LayoutDraft.texts(): List<String> = layout.rows[0].seatIds.map { layout.labels().labelOf(it)!!.seat }

    @Test
    fun widenedTheExistingSeatsKeepTheirNumbersAndUndoRestoresTheRow() {
        val draft = LayoutDraft.start(hall)
        val history = UndoHistory<LayoutDraft>()
        assertEquals(listOf("10", "9", "8", "7", "6", "5", "4", "3"), draft.texts())

        history.record(draft)
        val wider = draft.withColumnCount(12)
        assertEquals(CellType.SEAT, wider.layout.cell(0, 11).type)
        assertEquals(listOf("10", "9", "8", "7", "6", "5", "4", "3", "2"), wider.texts())
        assertEquals(listOf(2), wider.layout.rows[0].blockStartNumbers)

        val back = history.undo(wider)!!
        assertEquals(stored.rows, back.layout.rows)
        assertTrue(back.hasSameContentAs(draft))

        // Narrowed again, the row is as stored.
        assertEquals(stored.rows, wider.withColumnCount(11).layout.rows)
    }

    @Test
    fun belowOneTheNewSeatTakesTheStart() {
        // Two more columns use 2 and 1; a third has no number left and takes 1, the others move up.
        val draft = LayoutDraft.start(hall)
        assertEquals(listOf("10", "9", "8", "7", "6", "5", "4", "3", "2", "1"), draft.withColumnCount(13).texts())
        assertEquals((11 downTo 1).map { "$it" }, draft.withColumnCount(14).texts())
    }
}
