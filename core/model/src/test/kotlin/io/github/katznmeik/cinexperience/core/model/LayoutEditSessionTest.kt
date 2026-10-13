package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutEditSessionTest {

    private val original = layoutOf("SSS", "SSS") // ids 1..6
    private fun seatAt(layout: SeatLayout?, row: Int, column: Int) = (layout!!.cell(row, column) as LayoutCell.Seat).id

    @Test
    fun cyclingASeatAllTheWayRoundRestoresItsOriginalId() {
        var session = LayoutEditSession.start(original, hallNextSeatId = 7)
        session = session.cycleCell(0, 1) // GAP
        session = session.cycleCell(0, 1) // AISLE
        assertEquals(LayoutCell.Aisle, session.layout!!.cell(0, 1))
        session = session.cycleCell(0, 1) // SEAT again
        assertEquals(SeatId(2), seatAt(session.layout, 0, 1))
        assertTrue(session.removedSeatsWithData(original.seatIds).isEmpty())
        assertFalse(session.hasChanges)
    }

    @Test
    fun restoredIdFollowsInsertedAndRemovedRowsAndColumns() {
        var session = LayoutEditSession.start(original, 7)
            .setCellType(1, 2, CellType.GAP) // seat 6
            .insertRow(0)
            .insertColumn(0)
        session = session.setCellType(2, 3, CellType.SEAT) // same physical cell
        assertEquals(SeatId(6), seatAt(session.layout, 2, 3))
        assertTrue(session.layout!!.isEditOf(original)) // what the repository checks

        // Removing the row with a ghost drops it; the seat is gone for good.
        var other = LayoutEditSession.start(original, 7).setCellType(0, 0, CellType.GAP).removeRow(0)
        other = other.insertRow(0, fill = CellType.GAP).setCellType(0, 0, CellType.SEAT)
        assertTrue(seatAt(other.layout, 0, 0).value >= 7)
        assertEquals(listOf(SeatId(1), SeatId(2), SeatId(3)), other.removedSeatsWithData(original.seatIds).map { it.seatId })
    }

    @Test
    fun newSeatsStartAtTheHallMarkNotTheLayoutCounter() {
        // The stored layout ends at id 6, but the hall once had ids up to 12.
        val session = LayoutEditSession.start(original, hallNextSeatId = 13).insertRow(2)
        assertEquals(listOf(SeatId(13), SeatId(14), SeatId(15)), session.layout!!.rows[2].seatIds)
        val toggled = LayoutEditSession.start(original, 13)
            .setCellType(0, 0, CellType.GAP).setCellType(0, 1, CellType.GAP)
            .setCellType(0, 0, CellType.SEAT)
        assertEquals(SeatId(1), seatAt(toggled.layout, 0, 0)) // original restored
    }

    @Test
    fun newGridAfterClearNeverReusesIds() {
        // Reviewer scenario: 3×4 grid (ids 1..12), data on seat 12, then redraw 2×6.
        val first = SeatLayout.create(3, 4, firstSeatId = 1)
        val session = LayoutEditSession.start(first, hallNextSeatId = 13).clear().newGrid(2, 6)
        val redrawn = session.layout!!
        assertTrue(redrawn.seatIds.all { it.value >= 13 })
        assertEquals(
            listOf(SeatId(12)),
            session.removedSeatsWithData(setOf(SeatId(12))).map { it.seatId },
        )
        // A second new grid in the same session also gets fresh ids.
        val again = session.newGrid(1, 2).layout!!
        assertTrue(again.seatIds.all { it.value >= redrawn.nextSeatId })
    }

    @Test
    fun startingFromNoLayout() {
        val session = LayoutEditSession.start(null, hallNextSeatId = 20)
        assertNull(session.layout)
        assertFalse(session.hasChanges)
        assertFailsWith<IllegalStateException> { session.cycleCell(0, 0) }
        val drawn = session.newGrid(1, 3)
        assertEquals(listOf(SeatId(20), SeatId(21), SeatId(22)), drawn.layout!!.rows[0].seatIds)
        assertTrue(drawn.hasChanges)
        assertTrue(drawn.removedSeatsWithData(emptySet()).isEmpty())
    }

    @Test
    fun newSeatsInASessionAreNotRestoredAsOriginals() {
        var session = LayoutEditSession.start(original, 7).insertColumn(3) // ids 7, 8
        val added = seatAt(session.layout, 0, 3)
        session = session.setCellType(0, 3, CellType.GAP).setCellType(0, 3, CellType.SEAT)
        assertTrue(seatAt(session.layout, 0, 3).value > added.value)
    }

    @Test
    fun resizeAndRowSettingsWork() {
        val session = LayoutEditSession.start(original, 7)
            .resize(3, 4)
            .updateRow(0) { it.copy(label = "A") }
        val layout = session.layout!!
        assertEquals(3, layout.rowCount)
        assertEquals(4, layout.columnCount)
        assertEquals("A", layout.rows[0].label)
        assertTrue(original.seatIds.all { it in layout.seatIds })
        val shrunk = session.resize(1, 1)
        assertEquals(setOf(SeatId(1)), shrunk.layout!!.seatIds)
    }

    @Test
    fun hallStartsSessionWithItsMark() {
        val hall = Hall(id = 1, cinemaId = 1, name = "Saal", layout = original, nextSeatId = 30)
        val session = hall.startLayoutEdit().insertRow(0)
        assertEquals(SeatId(30), session.layout!!.rows[0].seatIds.first())
    }
}
