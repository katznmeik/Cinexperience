package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * Block start numbers follow their block's first numbered seat when a cell change
 * splits, merges, shortens or extends a row's blocks (spec §4.3: seat numbers must
 * match Yorck's booking, so a start set for Yorck's block must not land on another).
 */
class BlockStartReKeyingTest {

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

    private fun SeatLayout.restartPerBlock(row: Int = 0): SeatLayout =
        updateRow(row) { it.copy(numbering = SeatNumbering.RESTART_PER_BLOCK) }

    /** Kant Kino style: a right-to-left row with one block starting at 3, reading 10 … 3. */
    private val kant = layoutOf("...SSSSSSSS...").rightToLeft().withStarts(3)

    /** Cinema Paris row 7: `SSSSSSSS|||SSSSSSSS` with `[null, 12]`, reading 1–8 | 12–19. */
    private val paris = layoutOf("SSSSSSSS|||SSSSSSSS").withStarts(null, 12)

    @Test
    fun kantRightToLeftRowKeepsItsNumbersWhenAnAisleIsPaintedIn() {
        assertEquals(listOf("10", "9", "8", "7", "6", "5", "4", "3"), kant.texts())

        val split = kant.withCellType(0, 7, CellType.AISLE) // ...SSSS|SSS...
        // The start belongs to the rightmost seat (the first in numbering order), now in the right block.
        assertEquals(listOf(null, 3), split.starts())
        assertEquals(listOf("9", "8", "7", "6", "5", "4", "3"), split.texts())

        // An aisle at the other end of the row leaves the start where it is.
        val splitLeft = kant.withCellType(0, 4, CellType.AISLE) // ...S|SSSSSS...
        assertEquals(listOf(null, 3), splitLeft.starts())
        assertEquals(listOf("9", "8", "7", "6", "5", "4", "3"), splitLeft.texts())
    }

    @Test
    fun parisRow7KeepsItsRightBlockAt12WhenTheLeftBlockIsSplit() {
        assertEquals((1..8).map { "$it" } + (12..19).map { "$it" }, paris.texts())

        val split = paris.withCellType(0, 3, CellType.AISLE) // SSS|SSSS|||SSSSSSSS
        assertEquals(listOf(null, null, 12), split.starts())
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7") + (12..19).map { "$it" }, split.texts())

        // Splitting the right block: the start stays with its first seat, the split-off part counts on.
        val splitRight = paris.withCellType(0, 14, CellType.AISLE) // SSSSSSSS|||SSS|SSSS
        assertEquals(listOf(null, 12), splitRight.starts())
        assertEquals((1..8).map { "$it" } + listOf("12", "13", "14", "15", "16", "17", "18"), splitRight.texts())
    }

    @Test
    fun mergingTwoBlocksKeepsTheStartOfTheBlockThatComesFirstInNumberingOrder() {
        val row = layoutOf("SSS|SSS").withStarts(null, 10) // 1 2 3 | 10 11 12
        val merged = row.withCellType(0, 3, CellType.SEAT)
        assertEquals(emptyList(), merged.starts())
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7"), merged.texts())

        // Both blocks with a start: the first block's wins, the other is dropped.
        val both = layoutOf("SSS|SSS").withStarts(5, 20).withCellType(0, 3, CellType.GAP)
        assertEquals(listOf(5), both.starts())
        assertEquals(listOf("5", "6", "7", "8", "9", "10"), both.texts())

        // Right to left: the right block comes first, so its start is the one kept.
        val rtl = layoutOf("SSS|SSS").rightToLeft().withStarts(20, 5).withCellType(0, 3, CellType.SEAT)
        assertEquals(listOf(5), rtl.starts())
        assertEquals(listOf("11", "10", "9", "8", "7", "6", "5"), rtl.texts())
    }

    @Test
    fun removingAndInsertingAnAisleColumnMovesTheStartsWithTheirSeats() {
        val row = layoutOf("SS|SS|SS").withStarts(null, 5, 12) // 1 2 | 5 6 | 12 13
        assertEquals(listOf("1", "2", "5", "6", "12", "13"), row.texts())

        val merged = row.removeColumn(2) // SSSS|SS
        assertEquals(listOf(null, 12), merged.starts())
        assertEquals(listOf("1", "2", "3", "4", "12", "13"), merged.texts())

        val mergedRight = row.removeColumn(5) // SS|SSSS
        assertEquals(listOf(null, 5), mergedRight.starts())
        assertEquals(listOf("1", "2", "5", "6", "7", "8"), mergedRight.texts())

        val split = row.insertColumn(1, CellType.AISLE) // S|S|SS|SS
        assertEquals(listOf(null, null, 5, 12), split.starts())
        assertEquals(listOf("1", "2", "5", "6", "12", "13"), split.texts())

        // A seat column inserted at the front of a block joins it in front of its first seat:
        // the start stays with the block.
        val extended = row.insertColumn(3, CellType.SEAT) // SS|SSS|SS
        assertEquals(listOf(null, 5, 12), extended.starts())
        assertEquals(listOf("1", "2", "5", "6", "7", "12", "13"), extended.texts())

        // Resize shrinks through the same path.
        val shrunk = row.resize(1, 4) // SS|S
        assertEquals(listOf(null, 5), shrunk.starts())
        assertEquals(listOf("1", "2", "5"), shrunk.texts())
    }

    @Test
    fun theStartIsDroppedWhenItsSeatIsPaintedAway() {
        val row = layoutOf("SSS|SSS").withStarts(5, 10)
        val gone = row.withCellType(0, 4, CellType.GAP) // SSS|.SS
        assertEquals(listOf(5), gone.starts())
        assertEquals(listOf("5", "6", "7", "8", "9"), gone.texts())

        // A seat painted in front of the block's first seat joins the block; the start stays.
        val front = layoutOf("SSS|.SS").withStarts(5, 10).withCellType(0, 4, CellType.SEAT)
        assertEquals(listOf(5, 10), front.starts())
        assertEquals(listOf("5", "6", "7", "10", "11", "12"), front.texts())

        // Right to left: the first seat is the rightmost one.
        val rtl = layoutOf("SSS|SSS").rightToLeft().withStarts(10, 5).withCellType(0, 6, CellType.GAP)
        assertEquals(listOf(10), rtl.starts())
        assertEquals(listOf("12", "11", "10", "2", "1"), rtl.texts())
    }

    @Test
    fun fixedLabelsDoNotCarryAStart() {
        // "R" at the front of the block: the start is keyed on the first numbered seat.
        val row = layoutOf("WSS|SSS").withFixedLabel(0, 0, "R").withStarts(5, 10) // R 5 6 | 10 11 12
        assertEquals(listOf("R", "5", "6", "10", "11", "12"), row.texts())

        val withoutR = row.withCellType(0, 0, CellType.GAP) // .SS|SSS
        assertEquals(listOf(5, 10), withoutR.starts())
        assertEquals(listOf("5", "6", "10", "11", "12"), withoutR.texts())

        val longer = layoutOf("WSSS|SSS").withFixedLabel(0, 0, "R").withStarts(5, 10) // R 5 6 7 | 10 11 12
        val splitAfterR = longer.withCellType(0, 2, CellType.AISLE) // WS|S|SSS: the 5 stays with its seat beside the R
        assertEquals(listOf(5, null, 10), splitAfterR.starts())
        assertEquals(listOf("R", "5", "6", "10", "11", "12"), splitAfterR.texts())
        val rAlone = longer.withCellType(0, 1, CellType.AISLE) // W|SS|SSS: the seat the 5 was keyed on is gone
        assertEquals(listOf(null, null, 10), rAlone.starts())
        assertEquals(listOf("R", "1", "2", "10", "11", "12"), rAlone.texts())

        // Kant Kino Saal 4 style: right to left, the "R" at the left end (last in numbering order).
        val kant = layoutOf("WSSSSSS").rightToLeft().withFixedLabel(0, 0, "R").withStarts(3) // R 8 7 6 5 4 3
        assertEquals(listOf("R", "8", "7", "6", "5", "4", "3"), kant.texts())
        val kantSplit = kant.withCellType(0, 3, CellType.AISLE) // WSS|SSS
        assertEquals(listOf(null, 3), kantSplit.starts())
        assertEquals(listOf("R", "7", "6", "5", "4", "3"), kantSplit.texts())

        // A block of only fixed-label seats keeps its (unused) start on its first seat.
        val onlyR = layoutOf("W|S.S").withFixedLabel(0, 0, "R").withStarts(7, null)
        val onlyRSplit = onlyR.withCellType(0, 3, CellType.AISLE) // W|S|S
        assertEquals(listOf(7), onlyRSplit.starts())
        assertEquals(listOf("R", "1", "2"), onlyRSplit.texts())
    }

    @Test
    fun restartPerBlockSplitsRestartAtOneAndMergesLikeContinuousRows() {
        val row = layoutOf("SSSS|SSSS").restartPerBlock().withStarts(null, 20) // 1 2 3 4 | 20 21 22 23
        assertEquals(listOf("1", "2", "3", "4", "20", "21", "22", "23"), row.texts())

        val split = row.withCellType(0, 2, CellType.AISLE) // SS|S|SSSS: the split-off block restarts at 1
        assertEquals(listOf(null, null, 20), split.starts())
        assertEquals(listOf("1", "2", "1", "20", "21", "22", "23"), split.texts())

        val splitRight = row.withCellType(0, 7, CellType.AISLE) // SSSS|SS|S
        assertEquals(listOf(null, 20), splitRight.starts())
        assertEquals(listOf("1", "2", "3", "4", "20", "21", "1"), splitRight.texts())

        val merged = row.withCellType(0, 4, CellType.SEAT)
        assertEquals(emptyList(), merged.starts())
        assertEquals((1..9).map { "$it" }, merged.texts())
    }

    @Test
    fun unchangedBlocksKeepTheirStartsExactly() {
        val layout = layoutOf("SS|SS|SS", "SSSSSSSS", "...SSSSS").rightToLeft(2)
            .withStarts(null, 5, 12).withStarts(4, row = 1).withStarts(3, row = 2)

        // Changes that keep every block's seats keep every start.
        val widened = layout.insertColumn(0, CellType.GAP)
        assertEquals(layout.rows.map { it.blockStartNumbers }, widened.rows.map { it.blockStartNumbers })
        val narrowed = widened.removeColumn(0)
        assertEquals(layout.rows, narrowed.rows)
        val gapInside = layout.withCellType(2, 5, CellType.GAP) // a gap doesn't split
        assertEquals(listOf(3), gapInside.starts(2))
        assertEquals(listOf("6", "5", "4", "3"), gapInside.texts(2))
        val otherRow = layout.withCellType(1, 7, CellType.AISLE)
        assertEquals(listOf(null, 5, 12), otherRow.starts(0))
        assertEquals(listOf(4), otherRow.starts(1)) // a trailing aisle creates no block

        // Changes that don't touch cells never re-key.
        assertSame(layout, layout.withCellType(0, 0, CellType.SEAT))
        assertEquals(layout.starts(), layout.withSeatKind(0, 0, SeatKind.WHEELCHAIR).starts())
        assertEquals(layout.starts(), layout.withFixedLabel(0, 0, "R").starts())

        // The list is normalised: entries beyond the blocks and trailing nulls go.
        val over = layoutOf("SSS|SSS").withStarts(null, 10, 20).withCellType(0, 3, CellType.SEAT)
        assertEquals(emptyList(), over.starts())
    }

    @Test
    fun aSessionRestoresTheStartWithTheSeatPaintedAwayAndBack() {
        fun LayoutEditSession.texts() = layout!!.texts()
        val start = LayoutEditSession.start(kant, hallNextSeatId = 9)

        // The first seat (number 3) painted away loses the start; painted back it gets it back.
        val away = start.setCellType(0, 10, CellType.GAP)
        assertEquals(emptyList(), away.layout!!.starts())
        assertEquals(listOf("7", "6", "5", "4", "3", "2", "1"), away.texts())
        listOf(
            away.setCellType(0, 10, CellType.SEAT),
            away.setCellType(0, 10, CellType.AISLE).setSeat(0, 10, SeatKind.WHEELCHAIR),
            start.cycleCell(0, 10).cycleCell(0, 10).cycleCell(0, 10),
        ).forEach { back ->
            assertEquals(listOf(3), back.layout!!.starts())
            assertEquals(listOf("10", "9", "8", "7", "6", "5", "4", "3"), back.texts())
        }
        assertFalse(start.cycleCell(0, 10).cycleCell(0, 10).cycleCell(0, 10).hasChanges)

        // Paris: the start of 12 survives a round trip of its seat while the row is split elsewhere.
        val parisSession = LayoutEditSession.start(paris, hallNextSeatId = 17)
            .setCellType(0, 11, CellType.GAP)
            .setCellType(0, 3, CellType.AISLE)
            .setSeat(0, 11, SeatKind.STANDARD)
        assertEquals(listOf(null, null, 12), parisSession.layout!!.starts())
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7") + (12..19).map { "$it" }, parisSession.texts())

        // A start set on a block whose first seat is a ghost: the ghost's seat comes first, so
        // its start wins when it returns (the newer one is dropped; Undo has it).
        val replaced = away.updateRow(0) { it.copy(blockStartNumbers = listOf(7)) }.setCellType(0, 10, CellType.SEAT)
        assertEquals(listOf(3), replaced.layout!!.starts())

        // A new seat (not an original one) carries no start.
        val fresh = LayoutEditSession.start(layoutOf(".SS"), hallNextSeatId = 3)
            .setCellType(0, 0, CellType.SEAT)
            .updateRow(0) { it.copy(blockStartNumbers = listOf(5)) }
            .setCellType(0, 0, CellType.GAP)
            .setCellType(0, 0, CellType.SEAT)
        assertEquals(emptyList(), fresh.layout!!.starts())
    }
}
