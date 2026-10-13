package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SeatLayoutTest {

    private fun ids(vararg values: Int) = values.map(::SeatId)

    @Test
    fun createAssignsRowMajorIdsFromOne() {
        val layout = SeatLayout.create(rows = 2, columns = 3)
        assertEquals(2, layout.rowCount)
        assertEquals(3, layout.columnCount)
        assertEquals(ids(1, 2, 3), layout.rows[0].seatIds)
        assertEquals(ids(4, 5, 6), layout.rows[1].seatIds)
        assertEquals(7, layout.nextSeatId)
        assertEquals(CellPosition(1, 2), layout.positionOf(SeatId(6)))
    }

    @Test
    fun createWithGapFillHasNoSeats() {
        val layout = SeatLayout.create(rows = 2, columns = 2, fill = CellType.GAP)
        assertEquals(0, layout.seatCount)
        assertEquals(1, layout.nextSeatId)
    }

    @Test
    fun fromPatternsParsesAllCellTypes() {
        val layout = SeatLayout.fromPatterns("S.|S", "SSSS")
        assertEquals(LayoutCell.Seat(SeatId(1)), layout.cell(0, 0))
        assertEquals(LayoutCell.Gap, layout.cell(0, 1))
        assertEquals(LayoutCell.Aisle, layout.cell(0, 2))
        assertEquals(LayoutCell.Seat(SeatId(2)), layout.cell(0, 3))
        assertEquals(ids(3, 4, 5, 6), layout.rows[1].seatIds)
        assertEquals("S.|S", LayoutPattern.encode(layout.rows[0].cells))
    }

    @Test
    fun patternRejectsUnknownCharsAndWrongIdCount() {
        assertFailsWith<IllegalArgumentException> { SeatLayout.fromPatterns("SX") }
        assertFailsWith<IllegalArgumentException> { LayoutPattern.decode("SS", ids(1)) }
    }

    @Test
    fun validationRejectsBrokenLayouts() {
        assertFailsWith<IllegalArgumentException> { SeatLayout(emptyList(), 1) }
        assertFailsWith<IllegalArgumentException> { SeatLayout(listOf(LayoutRow(emptyList())), 1) }
        // Ragged rows.
        assertFailsWith<IllegalArgumentException> { SeatLayout.fromPatterns("SS", "S") }
        // Duplicate id.
        val seat = LayoutCell.Seat(SeatId(1))
        assertFailsWith<IllegalArgumentException> { SeatLayout(listOf(LayoutRow(listOf(seat, seat))), 2) }
        // Id not below nextSeatId.
        assertFailsWith<IllegalArgumentException> { SeatLayout(listOf(LayoutRow(listOf(seat))), 1) }
        // Blank label / negative block start.
        assertFailsWith<IllegalArgumentException> { LayoutRow(listOf(seat), label = " ") }
        assertFailsWith<IllegalArgumentException> { LayoutRow(listOf(seat), blockStartNumbers = listOf(-1)) }
        assertFailsWith<IllegalArgumentException> { SeatId(0) }
    }

    @Test
    fun insertingRowInFrontKeepsExistingIdsOnTheirSeats() {
        val layout = SeatLayout.fromPatterns("SSS", "SSS")
        val edited = layout.insertRow(0)
        assertEquals(3, edited.rowCount)
        assertEquals(ids(7, 8, 9), edited.rows[0].seatIds)
        assertEquals(ids(1, 2, 3), edited.rows[1].seatIds)
        assertEquals(ids(4, 5, 6), edited.rows[2].seatIds)
        assertEquals(10, edited.nextSeatId)
    }

    @Test
    fun insertingColumnKeepsIdsAndAllocatesNewOnes() {
        val layout = SeatLayout.fromPatterns("SS", "SS")
        val edited = layout.insertColumn(1, fill = CellType.AISLE)
        assertEquals("S|S", LayoutPattern.encode(edited.rows[0].cells))
        assertEquals(layout.seatIds, edited.seatIds)
        assertEquals(layout.nextSeatId, edited.nextSeatId)

        val withSeats = layout.insertColumn(0)
        assertEquals(ids(5, 1, 2), withSeats.rows[0].seatIds)
        assertEquals(ids(6, 3, 4), withSeats.rows[1].seatIds)
    }

    @Test
    fun removingRowsAndColumnsRemovesOnlyThoseSeats() {
        val layout = SeatLayout.fromPatterns("SSS", "SSS")
        assertEquals(ids(4, 5, 6).toSet(), layout.removeRow(0).seatIds)
        assertEquals(ids(1, 3, 4, 6).toSet(), layout.removeColumn(1).seatIds)
        assertFailsWith<IllegalArgumentException> { SeatLayout.fromPatterns("S").removeRow(0) }
        assertFailsWith<IllegalArgumentException> { SeatLayout.fromPatterns("S").removeColumn(0) }
    }

    @Test
    fun seatIdsAreNeverReusedAfterRemoval() {
        val layout = SeatLayout.fromPatterns("SS")
        val removed = layout.withCellType(0, 1, CellType.GAP)
        assertEquals(ids(1).toSet(), removed.seatIds)
        val redrawn = removed.withCellType(0, 1, CellType.SEAT)
        assertEquals(LayoutCell.Seat(SeatId(3)), redrawn.cell(0, 1))
        assertEquals(4, redrawn.nextSeatId)
    }

    @Test
    fun withCellTypeSameTypeIsNoOp() {
        val layout = SeatLayout.fromPatterns("SS")
        assertSame(layout, layout.withCellType(0, 0, CellType.SEAT))
    }

    @Test
    fun cycleCellGoesSeatGapAisleSeat() {
        var layout = SeatLayout.fromPatterns("S")
        layout = layout.cycleCell(0, 0)
        assertEquals(LayoutCell.Gap, layout.cell(0, 0))
        layout = layout.cycleCell(0, 0)
        assertEquals(LayoutCell.Aisle, layout.cell(0, 0))
        layout = layout.cycleCell(0, 0)
        assertEquals(CellType.SEAT, layout.cell(0, 0).type)
        assertEquals(SeatId(2), (layout.cell(0, 0) as LayoutCell.Seat).id)
    }

    @Test
    fun updateRowChangesSettingsButNotCells() {
        val layout = SeatLayout.fromPatterns("SS|SS")
        val edited = layout.updateRow(0) {
            it.copy(label = "A", numbering = SeatNumbering.RESTART_PER_BLOCK, halfSeatOffset = true)
        }
        assertEquals("A", edited.rows[0].label)
        assertTrue(edited.rows[0].halfSeatOffset)
        assertEquals(layout.seatIds, edited.seatIds)
        assertFailsWith<IllegalArgumentException> {
            layout.updateRow(0) { it.copy(cells = it.cells.reversed()) }
        }
    }

    @Test
    fun resizeGrowsAndShrinksAtBottomRight() {
        val layout = SeatLayout.fromPatterns("SS", "SS")
        val grown = layout.resize(3, 3)
        assertEquals(3, grown.rowCount)
        assertEquals(3, grown.columnCount)
        assertEquals(CellPosition(0, 0), grown.positionOf(SeatId(1)))
        assertEquals(CellPosition(1, 1), grown.positionOf(SeatId(4)))
        val shrunk = grown.resize(1, 1)
        assertEquals(ids(1).toSet(), shrunk.seatIds)
        assertNull(shrunk.positionOf(SeatId(2)))
    }
}
