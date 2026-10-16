package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A numbered seat added in front of a block with an explicit start (spec §4.3:
 * seat numbers must match Yorck's booking) takes the number before the start, so
 * every existing seat of the block keeps its number. Only where there is no
 * number left below 1 does the new seat take the start and the others move up.
 */
class BlockStartFrontSeatTest {

    /** Seat texts of [row], left to right as drawn. */
    private fun SeatLayout.texts(row: Int = 0): List<String> {
        val labels = labels()
        return rows[row].seatIds.map { labels.labelOf(it)!!.seat }
    }

    private fun SeatLayout.starts(row: Int = 0): List<Int?> = rows[row].blockStartNumbers

    private fun SeatLayout.withStarts(vararg starts: Int?, row: Int = 0): SeatLayout =
        updateRow(row) { it.copy(blockStartNumbers = starts.toList()) }

    private fun SeatLayout.rightToLeft(row: Int = 0): SeatLayout =
        updateRow(row) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }

    /** The labels of [ids] in this layout. */
    private fun SeatLayout.labelsOf(ids: Collection<SeatId>): List<SeatLabel?> {
        val labels = labels()
        return ids.map { labels.labelOf(it) }
    }

    /** Kant Kino style: a right-to-left row with one block starting at 3, reading 10 … 3. */
    private val kant = layoutOf("...SSSSSSSS...").rightToLeft().withStarts(3)

    @Test
    fun aSeatPaintedInFrontOfALeftToRightBlockTakesTheNumberBeforeItsStart() {
        val row = layoutOf(".SSS").withStarts(5) // . 5 6 7
        val front = row.withCellType(0, 0, CellType.SEAT)
        assertEquals(listOf(4), front.starts())
        assertEquals(listOf("4", "5", "6", "7"), front.texts())
        assertEquals(row.labelsOf(row.seatIds), front.labelsOf(row.seatIds))

        // A seat column inserted in front: the same.
        val inserted = layoutOf("SSS").withStarts(5).insertColumn(0, CellType.SEAT)
        assertEquals(listOf(4), inserted.starts())
        assertEquals(listOf("4", "5", "6", "7"), inserted.texts())

        // The second block of a split row: the first block is untouched.
        val split = layoutOf("SSS|.SS").withStarts(5, 10).withCellType(0, 4, CellType.SEAT)
        assertEquals(listOf(5, 9), split.starts())
        assertEquals(listOf("5", "6", "7", "9", "10", "11"), split.texts())

        // Across a gap the numbers still rise by one.
        val acrossGap = layoutOf("..SSS").withStarts(5).withCellType(0, 0, CellType.SEAT)
        assertEquals(listOf("4", "5", "6", "7"), acrossGap.texts())
    }

    @Test
    fun aSeatPaintedInFrontOfARightToLeftBlockTakesTheNumberBeforeItsStart() {
        assertEquals(listOf("10", "9", "8", "7", "6", "5", "4", "3"), kant.texts())
        val front = kant.withCellType(0, 11, CellType.SEAT) // the right end comes first
        assertEquals(listOf(2), front.starts())
        assertEquals(listOf("10", "9", "8", "7", "6", "5", "4", "3", "2"), front.texts())

        // A seat at the other end is behind the block: it counts on, the start stays.
        val behind = kant.withCellType(0, 2, CellType.SEAT)
        assertEquals(listOf(3), behind.starts())
        assertEquals(listOf("11", "10", "9", "8", "7", "6", "5", "4", "3"), behind.texts())

        // Every row of an inserted seat column gets the same rule.
        val twoRows = layoutOf("SSS", "SSS").rightToLeft(0).withStarts(4, row = 0).withStarts(7, row = 1)
        val wider = twoRows.insertColumn(0, CellType.SEAT) // behind row 0 (right to left), in front of row 1
        assertEquals(listOf("7", "6", "5", "4"), wider.texts(0))
        assertEquals(listOf("6", "7", "8", "9"), wider.texts(1))
        assertEquals(listOf(4), wider.starts(0))
        assertEquals(listOf(6), wider.starts(1))
    }

    @Test
    fun severalSeatsAddedInFrontAtOnceCountBackByTheirNumber() {
        val row = layoutOf("...SSS").withStarts(5).rows[0] // . . . 5 6 7
        val old = row.cells.drop(3)

        val two = row.withCells(listOf(LayoutCell.Gap, seat(20), seat(21)) + old)
        assertEquals(listOf(3), two.blockStartNumbers)
        assertEquals(listOf("3", "4", "5", "6", "7"), SeatLayout(listOf(two), 22).texts())

        // With a gap between them, and right to left.
        val gapBetween = row.withCells(listOf(seat(20), LayoutCell.Gap, seat(21)) + old)
        assertEquals(listOf("3", "4", "5", "6", "7"), SeatLayout(listOf(gapBetween), 22).texts())

        val rtl = layoutOf("SSS...").rightToLeft().withStarts(5).rows[0] // 7 6 5 . . .
        val rtlTwo = rtl.withCells(rtl.cells.take(3) + listOf(seat(20), LayoutCell.Gap, seat(21)))
        assertEquals(listOf(3), rtlTwo.blockStartNumbers)
        assertEquals(listOf("7", "6", "5", "4", "3"), SeatLayout(listOf(rtlTwo), 22).texts())

        // Not enough numbers below the start: today's rule, the first new seat takes the start.
        val low = layoutOf("..SS").withStarts(2).rows[0] // . . 2 3
        val lowTwo = low.withCells(listOf(seat(20), seat(21)) + low.cells.drop(2))
        assertEquals(listOf(2), lowTwo.blockStartNumbers)
        assertEquals(listOf("2", "3", "4", "5"), SeatLayout(listOf(lowTwo), 22).texts())
    }

    @Test
    fun aBlockStartingAtOneKeepsTodaysRule() {
        val row = layoutOf(".SSS").withStarts(1) // there is no seat 0
        val front = row.withCellType(0, 0, CellType.SEAT)
        assertEquals(listOf(1), front.starts())
        assertEquals(listOf("1", "2", "3", "4"), front.texts())

        val zero = layoutOf(".SS").withStarts(0).withCellType(0, 0, CellType.SEAT)
        assertEquals(listOf(0), zero.starts())
        assertEquals(listOf("0", "1", "2"), zero.texts())

        val rtl = layoutOf("SSS.").rightToLeft().withStarts(1).withCellType(0, 3, CellType.SEAT)
        assertEquals(listOf(1), rtl.starts())
        assertEquals(listOf("4", "3", "2", "1"), rtl.texts())
    }

    @Test
    fun automaticBlocksAreUnchanged() {
        val automatic = layoutOf(".SSS").withCellType(0, 0, CellType.SEAT)
        assertEquals(emptyList(), automatic.starts())
        assertEquals(listOf("1", "2", "3", "4"), automatic.texts())

        // A continuous second block counts on from the first.
        val continuous = layoutOf("SSS|.SS").withStarts(5).withCellType(0, 4, CellType.SEAT)
        assertEquals(listOf(5), continuous.starts())
        assertEquals(listOf("5", "6", "7", "8", "9", "10"), continuous.texts())

        // A restarting block restarts at 1.
        val restart = layoutOf("SS|.SS").updateRow(0) { it.copy(numbering = SeatNumbering.RESTART_PER_BLOCK) }
            .withStarts(4).withCellType(0, 3, CellType.SEAT)
        assertEquals(listOf(4), restart.starts())
        assertEquals(listOf("4", "5", "1", "2", "3"), restart.texts())
    }

    @Test
    fun fixedLabelSeatsAreNotFrontSeats() {
        // An "R" put in front uses no number: the start stays where it is.
        val row = layoutOf(".SSS").withStarts(5)
        val withR = row.withSeat(0, 0, SeatId(10), SeatKind.WHEELCHAIR, fixedLabel = "R")
        assertEquals(listOf(5), withR.starts())
        assertEquals(listOf("R", "5", "6", "7"), withR.texts())

        // A numbered seat in front of the "R" is in front of the first numbered seat: it takes 4.
        val beforeR = layoutOf(".WSSS").withFixedLabel(0, 1, "R").withStarts(5) // . R 5 6 7
        val front = beforeR.withCellType(0, 0, CellType.SEAT)
        assertEquals(listOf(4), front.starts())
        assertEquals(listOf("4", "R", "5", "6", "7"), front.texts())

        // Right to left, the "R" first in numbering order.
        val rtl = layoutOf(".SSSW").rightToLeft().withFixedLabel(0, 4, "R").withStarts(3) // . 5 4 3 R
        val rtlFront = rtl.insertColumn(5, CellType.SEAT)
        assertEquals(listOf("5", "4", "3", "R", "2"), rtlFront.texts())

        // A block of only fixed-label seats has no number to count back from.
        val alone = layoutOf("W..").withFixedLabel(0, 0, "R").withStarts(7)
        assertEquals(listOf("R", "1"), alone.withCellType(0, 1, CellType.SEAT).texts())
    }

    @Test
    fun theStartMovingOnAndSeatsAddedInFrontCompose() {
        // The first seat removed: the start moves on to 4. A new seat painted there takes 3 again.
        var layout = kant
        repeat(3) {
            val removed = layout.withCellType(0, 10, CellType.GAP)
            assertEquals(listOf(4), removed.starts())
            assertEquals(listOf("10", "9", "8", "7", "6", "5", "4"), removed.texts())
            layout = removed.withCellType(0, 10, CellType.SEAT)
            assertEquals(listOf(3), layout.starts())
            assertEquals(listOf("10", "9", "8", "7", "6", "5", "4", "3"), layout.texts())
        }

        // A new seat in front, removed again: the start moves back on to the old first seat.
        val front = kant.withCellType(0, 11, CellType.SEAT)
        val removedAgain = front.withCellType(0, 11, CellType.GAP)
        assertEquals(kant.rows, removedAgain.rows)

        // The first seat removed, then a seat painted further in front, across the gap.
        val skip = kant.withCellType(0, 10, CellType.GAP).withCellType(0, 11, CellType.SEAT)
        assertEquals(listOf(3), skip.starts())
        assertEquals(listOf("10", "9", "8", "7", "6", "5", "4", "3"), skip.texts())
    }

    @Test
    fun aSessionStillRestoresOriginalSeatsExactly() {
        val session = LayoutEditSession.start(kant, hallNextSeatId = 9)

        // The original first seat painted away and back gets its own start back.
        val roundTrip = session.setCellType(0, 10, CellType.GAP).setCellType(0, 10, CellType.SEAT)
        assertEquals(kant.rows, roundTrip.layout!!.rows)
        assertFalse(roundTrip.hasChanges)

        // Even after a new start was set while it was away: the original seat's own start wins.
        val replaced = session.setCellType(0, 10, CellType.GAP)
            .updateRow(0) { it.copy(blockStartNumbers = listOf(20)) }
            .setCellType(0, 10, CellType.SEAT)
        assertEquals(listOf(3), replaced.layout!!.starts())

        // A new seat painted in front and away again leaves no change.
        val frontAndBack = session.setCellType(0, 11, CellType.SEAT).setCellType(0, 11, CellType.GAP)
        assertEquals(kant.rows, frontAndBack.layout!!.rows)
        assertFalse(frontAndBack.hasChanges)

        // First seat away, a new seat in front of the gap, that seat away, the first seat back.
        val mixed = session.setCellType(0, 10, CellType.GAP)
            .setCellType(0, 11, CellType.SEAT)
            .also { assertEquals(listOf("10", "9", "8", "7", "6", "5", "4", "3"), it.layout!!.texts()) }
            .setCellType(0, 11, CellType.GAP)
            .setCellType(0, 10, CellType.SEAT)
        assertEquals(kant.rows, mixed.layout!!.rows)
        assertFalse(mixed.hasChanges)

        // Two seats painted away, the later one in numbering order first: painted back in either order.
        val ltr = layoutOf("SSSS").withStarts(5) // 5 6 7 8
        val ltrSession = LayoutEditSession.start(ltr, hallNextSeatId = 5)
        val away = ltrSession.setCellType(0, 1, CellType.GAP).setCellType(0, 0, CellType.GAP) // . . 6 7
        assertEquals(listOf("6", "7"), away.layout!!.texts())
        listOf(
            away.setCellType(0, 1, CellType.SEAT).setCellType(0, 0, CellType.SEAT),
            away.setCellType(0, 0, CellType.SEAT).setCellType(0, 1, CellType.SEAT),
        ).forEach { back ->
            assertEquals(ltr.rows, back.layout!!.rows)
            assertFalse(back.hasChanges)
        }
        // The second seat painted back alone is in front of the 6: it takes 5, the others keep theirs.
        assertEquals(listOf("5", "6", "7"), away.setCellType(0, 1, CellType.SEAT).layout!!.texts())
    }

    @Test
    fun rowsThatGetNoSeatInFrontKeepTheirStoredList() {
        // As older data may hold it: a trailing null. A seat behind the block doesn't change the start.
        val stored = layoutOf(".SSS.", ".SSS.").withStarts(5, null).withStarts(5, null, row = 1)
        val behind = stored.withCellType(0, 4, CellType.SEAT)
        assertEquals(listOf(5, null), behind.starts())
        assertEquals(listOf("5", "6", "7", "8"), behind.texts())

        val behindColumn = stored.insertColumn(5, CellType.SEAT)
        assertEquals(listOf(listOf(5, null), listOf(5, null)), behindColumn.rows.map { it.blockStartNumbers })

        // A seat in front of row 0 leaves row 1 exactly as stored.
        val front = stored.withCellType(0, 0, CellType.SEAT)
        assertEquals(listOf(4), front.starts())
        assertEquals(stored.rows[1], front.rows[1])

        // A seat inserted inside a block still moves the seats behind it up.
        val inside = layoutOf("SS.SS").withStarts(5).withCellType(0, 2, CellType.SEAT)
        assertEquals(listOf(5), inside.starts())
        assertEquals(listOf("5", "6", "7", "8", "9"), inside.texts())
    }

    private fun seat(id: Int) = LayoutCell.Seat(SeatId(id))
}
