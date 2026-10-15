package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Removing a column (or shrinking the grid) after painting over a seat: the
 * painted-over seat is gone for good, so a seat painted later in its place must
 * not revive its id or its fixed label (spec §4.3, §6.5). A painted-over seat in
 * a column that stays keeps its id and label, also when it shifts.
 */
class LayoutEditSessionColumnTest {

    /** Hall mark above the layout's own ids: new seats start at [MARK]. */
    private fun start(layout: SeatLayout) = LayoutEditSession.start(layout, hallNextSeatId = MARK)

    private fun seat(session: LayoutEditSession, row: Int, column: Int): LayoutCell.Seat =
        session.layout!!.cell(row, column) as LayoutCell.Seat

    @Test
    fun aSeatPaintedOverAndThenRemovedWithItsColumnNeverComesBack() {
        // Row 2: seats 4, 5 ("R"), 6.
        val labelled = layoutOf("SSS", "SSS").withFixedLabel(1, 1, "R")
        val removed = start(labelled)
            .setCellType(1, 1, CellType.GAP)
            .removeColumn(1)
        assertFalse(SeatId(5) in removed.layout!!.seatIds)

        val repainted = removed
            .insertColumn(1, fill = CellType.GAP)
            .setCellType(1, 1, CellType.SEAT)
        val newSeat = seat(repainted, 1, 1)
        assertTrue(newSeat.id.value >= MARK, "new id, got ${newSeat.id}")
        assertNull(newSeat.fixedLabel)
        assertFalse(SeatId(5) in repainted.layout!!.seatIds)
        assertEquals(
            setOf(SeatId(2), SeatId(5)),
            repainted.removedSeatsWithData(labelled.seatIds).map { it.seatId }.toSet(),
        )
        // The neighbours keep their ids.
        assertEquals(listOf(SeatId(4), newSeat.id, SeatId(6)), repainted.layout!!.rows[1].seatIds)

        // An inserted column of seats gets new ids too.
        val filled = removed.insertColumn(1)
        assertTrue(filled.layout!!.rows.all { row -> row.seatIds[1].value >= MARK })
        assertTrue(filled.layout!!.rows.flatMap { row -> row.cells }.none { it is LayoutCell.Seat && it.fixedLabel != null })
    }

    @Test
    fun aSeatPaintedOverAndThenCutOffByResizeNeverComesBack() {
        // Row 2: seats 4, 5, 6 ("R") at the right edge.
        val labelled = layoutOf("SSS", "SSS").withFixedLabel(1, 2, "R")
        val shrunk = start(labelled)
            .setCellType(1, 2, CellType.GAP)
            .resize(2, 2)
        assertEquals(2, shrunk.layout!!.columnCount)

        // Growing again with seats: new ids, no label.
        val grown = shrunk.resize(2, 3)
        val newSeat = seat(grown, 1, 2)
        assertTrue(newSeat.id.value >= MARK, "new id, got ${newSeat.id}")
        assertNull(newSeat.fixedLabel)

        // Growing with gaps and painting the cell: new id, no label.
        val repainted = shrunk.resize(2, 3, fill = CellType.GAP).setCellType(1, 2, CellType.SEAT)
        val painted = seat(repainted, 1, 2)
        assertTrue(painted.id.value >= MARK, "new id, got ${painted.id}")
        assertNull(painted.fixedLabel)
        assertFalse(SeatId(6) in repainted.layout!!.seatIds)
        assertFalse(SeatId(3) in repainted.layout!!.seatIds)
    }

    @Test
    fun aSeatPaintedOverRightOfARemovedColumnComesBackWithItsIdAfterShifting() {
        // Row 2: seats 5, 6, 7, 8 ("R").
        val labelled = layoutOf("SSSS", "SSSS").withFixedLabel(1, 3, "R")
        val shifted = start(labelled)
            .setCellType(1, 3, CellType.GAP) // seat 8
            .setCellType(0, 0, CellType.GAP) // seat 1, left of the removed column
            .removeColumn(1)
        assertEquals(3, shifted.layout!!.columnCount)

        // Seat 8 moved from column 3 to column 2.
        val restored = shifted.setCellType(1, 2, CellType.SEAT).setCellType(0, 0, CellType.SEAT)
        assertEquals(LayoutCell.Seat(SeatId(8), fixedLabel = "R"), seat(restored, 1, 2))
        assertEquals(LayoutCell.Seat(SeatId(1)), seat(restored, 0, 0))
        // Only the removed column's seats are gone.
        assertEquals(
            setOf(SeatId(2), SeatId(6)),
            restored.removedSeatsWithData(labelled.seatIds).map { it.seatId }.toSet(),
        )
        assertTrue(restored.layout!!.isEditOf(labelled))
    }

    private companion object {
        const val MARK = 20
    }
}
