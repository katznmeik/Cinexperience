package io.github.katznmeik.cinexperience.core.model

import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_COLUMNS
import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_ROWS
import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_SEAT_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The size and seat id limits of a layout ([MAX_ROWS], [MAX_COLUMNS],
 * [MAX_SEAT_ID]): a layout beyond them can't exist, and handing out a seat id at
 * the limit fails cleanly instead of wrapping around to a negative id.
 */
class SeatLayoutLimitsTest {

    private fun gaps(rows: Int, columns: Int): List<LayoutRow> = List(rows) { LayoutRow(List(columns) { LayoutCell.Gap }) }

    /** Fails with an [IllegalArgumentException] that is not the wrapped-around "SeatId must be positive". */
    private fun assertCleanFailure(block: () -> Unit) {
        val e = assertFailsWith<IllegalArgumentException> { block() }
        assertFalse(e.message.orEmpty().contains("must be positive"), "Wrapped around: ${e.message}")
    }

    @Test
    fun limitsAreTheEditorsGridAndABillionIds() {
        assertEquals(50, MAX_ROWS)
        assertEquals(60, MAX_COLUMNS)
        assertEquals(1_000_000_000, MAX_SEAT_ID)
    }

    @Test
    fun moreRowsThanTheLimitAreRejected() {
        assertFailsWith<IllegalArgumentException> { SeatLayout(gaps(MAX_ROWS + 1, 1), nextSeatId = 1) }
        assertFailsWith<IllegalArgumentException> { SeatLayout.create(MAX_ROWS + 1, 1, firstSeatId = 1) }
        assertFailsWith<IllegalArgumentException> { SeatLayout.fromPatterns(List(MAX_ROWS + 1) { "S" }, firstSeatId = 1) }
    }

    @Test
    fun moreColumnsThanTheLimitAreRejected() {
        assertFailsWith<IllegalArgumentException> { SeatLayout(gaps(1, MAX_COLUMNS + 1), nextSeatId = 1) }
        assertFailsWith<IllegalArgumentException> { SeatLayout.create(1, MAX_COLUMNS + 1, firstSeatId = 1) }
        assertFailsWith<IllegalArgumentException> { SeatLayout.fromPatterns("S".repeat(MAX_COLUMNS + 1), firstSeatId = 1) }
    }

    @Test
    fun nextSeatIdOutsideItsRangeIsRejected() {
        for (next in listOf(0, -5, Int.MIN_VALUE, MAX_SEAT_ID + 1, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException>("$next") { SeatLayout(gaps(1, 1), nextSeatId = next) }
        }
        assertEquals(1, SeatLayout(gaps(1, 1), nextSeatId = 1).nextSeatId)
        assertEquals(MAX_SEAT_ID, SeatLayout(gaps(1, 1), nextSeatId = MAX_SEAT_ID).nextSeatId)
    }

    @Test
    fun theLargestGridWithTheHighestIdsIsValid() {
        val seats = MAX_ROWS * MAX_COLUMNS
        val layout = SeatLayout.create(MAX_ROWS, MAX_COLUMNS, firstSeatId = MAX_SEAT_ID - seats)
        assertEquals(MAX_ROWS, layout.rowCount)
        assertEquals(MAX_COLUMNS, layout.columnCount)
        assertEquals(seats, layout.seatCount)
        assertEquals(MAX_SEAT_ID, layout.nextSeatId)
        assertEquals(SeatId(MAX_SEAT_ID - 1), layout.rows.last().seatIds.last())
        assertEquals(MAX_SEAT_ID, SeatLayout(gaps(MAX_ROWS, MAX_COLUMNS), nextSeatId = MAX_SEAT_ID).nextSeatId)
    }

    @Test
    fun newGridsStopAtTheIdLimit() {
        assertCleanFailure { SeatLayout.create(1, 2, firstSeatId = MAX_SEAT_ID - 1) }
        assertCleanFailure { SeatLayout.create(2, 2, firstSeatId = Int.MAX_VALUE - 1) }
        assertCleanFailure { SeatLayout.fromPatterns("SS", firstSeatId = MAX_SEAT_ID - 1) }
        assertCleanFailure { SeatLayout.fromPatterns("S", "S", firstSeatId = Int.MAX_VALUE) }
        assertEquals(MAX_SEAT_ID, SeatLayout.fromPatterns("S.S", firstSeatId = MAX_SEAT_ID - 2).nextSeatId)
    }

    @Test
    fun editsStopAtTheIdLimit() {
        val full = SeatLayout.fromPatterns("S.", firstSeatId = MAX_SEAT_ID - 1)
        assertEquals(MAX_SEAT_ID, full.nextSeatId)
        assertCleanFailure { full.withCellType(0, 1, CellType.SEAT) }
        assertCleanFailure { full.insertRow(1) }
        assertCleanFailure { full.insertColumn(2) }
        assertCleanFailure { full.withSeat(0, 1, SeatId(MAX_SEAT_ID)) }
        assertCleanFailure { full.withSeat(0, 1, SeatId(Int.MAX_VALUE)) }
        // Edits that need no new id still work.
        assertEquals(2, full.insertRow(1, fill = CellType.GAP).rowCount)
        assertEquals(CellType.AISLE, full.withCellType(0, 1, CellType.AISLE).cell(0, 1).type)
    }

    @Test
    fun rowsAndColumnsStopAtTheSizeLimit() {
        assertFailsWith<IllegalArgumentException> { SeatLayout.create(MAX_ROWS, 1, firstSeatId = 1).insertRow(0) }
        assertFailsWith<IllegalArgumentException> { SeatLayout.create(1, MAX_COLUMNS, firstSeatId = 1).insertColumn(0) }
        assertFailsWith<IllegalArgumentException> { SeatLayout.create(1, 1, firstSeatId = 1).resize(MAX_ROWS + 1, 1) }
    }

    @Test
    fun shiftingIdsStopsAtTheIdLimit() {
        val layout = SeatLayout.fromPatterns("SSS", firstSeatId = 1) // ids 1..3, next 4
        val highest = layout.withIdsShiftedBy(MAX_SEAT_ID - 4)
        assertEquals(MAX_SEAT_ID, highest.nextSeatId)
        assertEquals(SeatId(MAX_SEAT_ID - 1), highest.rows[0].seatIds.last())
        assertCleanFailure { layout.withIdsShiftedBy(MAX_SEAT_ID - 3) }
        assertCleanFailure { layout.withIdsShiftedBy(Int.MAX_VALUE - 2) }
    }

    @Test
    fun anEditSessionNeverHandsOutIdsPastTheLimit() {
        val nearTheLimit = Hall(cinemaId = 1, name = "Saal 1", nextSeatId = MAX_SEAT_ID - 5)
        assertCleanFailure { nearTheLimit.startLayoutEdit().newGrid(10, 16) }
        assertEquals(MAX_SEAT_ID, nearTheLimit.startLayoutEdit().newGrid(1, 5).layout!!.nextSeatId)
        // A hall mark above the limit (only from damaged data) can't start an edit at all.
        val beyond = Hall(cinemaId = 1, name = "Saal 1", layout = layoutOf("SS"), nextSeatId = MAX_SEAT_ID + 1)
        assertFailsWith<IllegalArgumentException> { beyond.startLayoutEdit() }
    }
}
