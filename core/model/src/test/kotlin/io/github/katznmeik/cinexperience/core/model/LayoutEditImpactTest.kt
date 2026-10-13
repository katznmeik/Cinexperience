package io.github.katznmeik.cinexperience.core.model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutEditImpactTest {

    private val old = SeatLayout.fromPatterns("SSS", "SSS")
    private fun visit(seat: Int?) = Visit(hallId = 1, date = LocalDate.of(2026, 1, 1), seatId = seat?.let(::SeatId))

    @Test
    fun seatsWithDataIncludesVisitSeatsAndNonEmptyOverrides() {
        val data = seatsWithData(
            visits = listOf(visit(1), visit(null), visit(1)),
            overrides = listOf(
                SeatOverride(1, SeatId(2), colour = SeatColour.RED),
                SeatOverride(1, SeatId(3)), // empty, carries nothing
                SeatOverride(1, SeatId(4), isFavourite = true),
            ),
        )
        assertEquals(setOf(SeatId(1), SeatId(2), SeatId(4)), data)
    }

    @Test
    fun removingSeatWithDataIsReportedWithOldLabel() {
        val new = old.withCellType(1, 1, CellType.GAP) // seat id 5, row 2 seat 2
        val removed = removedSeatsWithData(old, new, setOf(SeatId(5), SeatId(1)))
        assertEquals(listOf(RemovedSeat(SeatId(5), SeatLabel("2", 2))), removed)
    }

    @Test
    fun removingSeatWithoutDataIsNotReported() {
        val new = old.withCellType(1, 1, CellType.AISLE)
        assertTrue(removedSeatsWithData(old, new, setOf(SeatId(1))).isEmpty())
    }

    @Test
    fun relabellingRenumberingAndInsertingRowsRemoveNothing() {
        val all = old.seatIds
        val new = old.insertRow(0)
            .insertColumn(0)
            .updateRow(1) { it.copy(label = "A", blockStartNumbers = listOf(10)) }
        assertTrue(removedSeatsWithData(old, new, all).isEmpty())
    }

    @Test
    fun removingRowReportsAllItsSeatsWithDataInOrder() {
        val removed = removedSeatsWithData(old, old.removeRow(0), setOf(SeatId(3), SeatId(1), SeatId(4)))
        assertEquals(listOf(SeatId(1), SeatId(3)), removed.map { it.seatId })
    }

    @Test
    fun turningSeatToGapAndBackStillReportsTheOldSeat() {
        // The redrawn cell gets a new id, so data of the old seat would be orphaned.
        val new = old.withCellType(0, 0, CellType.GAP).withCellType(0, 0, CellType.SEAT)
        assertEquals(listOf(SeatId(1)), removedSeatsWithData(old, new, setOf(SeatId(1))).map { it.seatId })
    }

    @Test
    fun nullLayouts() {
        assertTrue(removedSeatsWithData(null, old, setOf(SeatId(1))).isEmpty())
        assertEquals(listOf(SeatId(2)), removedSeatsWithData(old, null, setOf(SeatId(2))).map { it.seatId })
    }
}
