package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Block start re-keying (spec §4.3) where a block holds only fixed-label seats
 * ("R"): such a block uses no number, so its start is unused and must stay
 * unused when it merges with numbered seats, and it must not outrank the start
 * of the numbered block it merges with. And rows whose blocks keep their starts
 * keep their stored list exactly, even an unnormalised one from older data.
 */
class BlockStartFixedLabelMergeTest {

    private fun SeatLayout.texts(row: Int = 0): List<String> {
        val labels = labels()
        return rows[row].seatIds.map { labels.labelOf(it)!!.seat }
    }

    private fun SeatLayout.starts(row: Int = 0): List<Int?> = rows[row].blockStartNumbers

    private fun SeatLayout.withStarts(vararg starts: Int?): SeatLayout =
        updateRow(0) { it.copy(blockStartNumbers = starts.toList()) }

    private fun SeatLayout.rightToLeft(): SeatLayout =
        updateRow(0) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }

    @Test
    fun mergingANumberedBlockWithAFixedLabelBlockKeepsTheNumberedBlocksStart() {
        // Right to left, the "R" alone in the rightmost block (first in numbering order).
        val rtl = layoutOf("SSS|W").rightToLeft().withFixedLabel(0, 4, "R").withStarts(3)
        assertEquals(listOf("5", "4", "3", "R"), rtl.texts())
        val rtlMerged = rtl.withCellType(0, 3, CellType.GAP) // SSS.W
        assertEquals(listOf(3), rtlMerged.starts())
        assertEquals(listOf("5", "4", "3", "R"), rtlMerged.texts())

        // Left to right, the "R" alone in the leftmost block; the aisle column removed.
        val ltr = layoutOf("W|SSS").withFixedLabel(0, 0, "R").withStarts(null, 5)
        assertEquals(listOf("R", "5", "6", "7"), ltr.texts())
        val ltrMerged = ltr.removeColumn(1) // WSSS
        assertEquals(listOf(5), ltrMerged.starts())
        assertEquals(listOf("R", "5", "6", "7"), ltrMerged.texts())
    }

    @Test
    fun theUnusedStartOfAFixedLabelBlockStaysUnused() {
        val row = layoutOf("W|S.S").withFixedLabel(0, 0, "R").withStarts(7) // R | 1 . 2, the 7 is unused
        assertEquals(listOf("R", "1", "2"), row.texts())

        val merged = row.withCellType(0, 1, CellType.GAP) // W.S.S
        assertEquals(listOf("R", "1", "2"), merged.texts())
        assertEquals(emptyList(), merged.starts())

        // A new seat painted beside the "R" doesn't pick the unused start up either.
        val alone = layoutOf("W..").withFixedLabel(0, 0, "R").withStarts(7)
        assertEquals(listOf("R", "1"), alone.withCellType(0, 1, CellType.SEAT).texts())
        assertEquals(listOf("R", "1"), alone.insertColumn(1, CellType.SEAT).texts())
    }

    @Test
    fun rowsWhoseStartsDontChangeKeepTheirStoredListExactly() {
        // As older data may hold them: a trailing null, an entry beyond the row's blocks.
        val stored = layoutOf("SSSSSSS", "SSS|SSS", "SS|SS..").updateRow(0) { it.copy(blockStartNumbers = listOf(3, null)) }
            .updateRow(1) { it.copy(blockStartNumbers = listOf(null, 12, 20)) }
            .updateRow(2) { it.copy(blockStartNumbers = listOf(null, null)) }

        val widened = stored.insertColumn(stored.columnCount, CellType.GAP)
        assertEquals(stored.rows.map { it.blockStartNumbers }, widened.rows.map { it.blockStartNumbers })
        assertEquals(stored.rows, widened.removeColumn(stored.columnCount).rows)
        assertEquals(listOf(listOf(3, null), listOf(null, 12, 20), listOf(null, null)), widened.rows.map { it.blockStartNumbers })

        // A gap painted inside a block changes no start either.
        assertEquals(listOf(null, 12, 20), stored.withCellType(1, 5, CellType.GAP).starts(1))

        // So a session that adds and removes a column has no changes.
        val session = LayoutEditSession.start(stored, hallNextSeatId = stored.nextSeatId)
            .insertColumn(0, CellType.GAP)
            .removeColumn(0)
        assertFalse(session.hasChanges)

        // A row whose blocks do change gets a normalised list.
        val merged = stored.withCellType(1, 3, CellType.SEAT) // SSSSSSS
        assertEquals(emptyList(), merged.starts(1))
    }
}
