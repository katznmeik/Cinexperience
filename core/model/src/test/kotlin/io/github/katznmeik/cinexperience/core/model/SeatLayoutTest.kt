package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SeatLayoutTest {

    private fun ids(vararg values: Int) = values.map(::SeatId)

    @Test
    fun createAssignsRowMajorIdsFromOne() {
        val layout = SeatLayout.create(rows = 2, columns = 3, firstSeatId = 1)
        assertEquals(2, layout.rowCount)
        assertEquals(3, layout.columnCount)
        assertEquals(ids(1, 2, 3), layout.rows[0].seatIds)
        assertEquals(ids(4, 5, 6), layout.rows[1].seatIds)
        assertEquals(7, layout.nextSeatId)
        assertEquals(CellPosition(1, 2), layout.positionOf(SeatId(6)))
    }

    @Test
    fun createAndPatternsStartAtTheGivenId() {
        val created = SeatLayout.create(rows = 1, columns = 2, firstSeatId = 13)
        assertEquals(ids(13, 14), created.rows[0].seatIds)
        assertEquals(15, created.nextSeatId)
        val parsed = SeatLayout.fromPatterns("S.S", firstSeatId = 7)
        assertEquals(ids(7, 8), parsed.rows[0].seatIds)
        assertEquals(9, parsed.nextSeatId)
        assertFailsWith<IllegalArgumentException> { SeatLayout.create(1, 1, firstSeatId = 0) }
        assertFailsWith<IllegalArgumentException> { SeatLayout.fromPatterns("S", firstSeatId = 0) }
    }

    @Test
    fun withSeatPlacesAGivenUnusedId() {
        val layout = layoutOf("S.S")
        val restored = layout.withSeat(0, 1, SeatId(9))
        assertEquals(ids(1, 9, 2), restored.rows[0].seatIds)
        assertEquals(10, restored.nextSeatId)
        assertEquals(4, layout.withSeat(0, 1, SeatId(3)).nextSeatId)
        assertFailsWith<IllegalArgumentException> { layout.withSeat(0, 0, SeatId(9)) } // already a seat
        assertFailsWith<IllegalArgumentException> { layout.withSeat(0, 1, SeatId(2)) } // id in use
    }

    @Test
    fun withIdsShiftedByMovesEveryIdAndTheCounter() {
        val layout = layoutOf("SS", ".S")
        val shifted = layout.withIdsShiftedBy(10)
        assertEquals(ids(11, 12), shifted.rows[0].seatIds)
        assertEquals(ids(13), shifted.rows[1].seatIds)
        assertEquals(14, shifted.nextSeatId)
        assertSame(layout, layout.withIdsShiftedBy(0))
        assertFailsWith<IllegalArgumentException> { layout.withIdsShiftedBy(-1) }
    }

    @Test
    fun isEditOfAcceptsStructuralEditsAndRejectsRenumberedNewGrids() {
        val old = SeatLayout.create(3, 4, firstSeatId = 1)
        assertTrue(old.isEditOf(old))
        val edited = old.insertRow(0).insertColumn(2).removeRow(2).removeColumn(0)
            .withCellType(0, 0, CellType.GAP).withCellType(1, 1, CellType.AISLE)
            .updateRow(1) { it.copy(label = "A", halfSeatOffset = true) }
        assertTrue(edited.isEditOf(old))
        // A new grid numbered from 1 again: shared ids land in other relative places.
        assertFalse(SeatLayout.create(2, 6, firstSeatId = 1).isEditOf(old))
        assertFalse(SeatLayout.create(4, 3, firstSeatId = 1).isEditOf(old))
        assertFalse(SeatLayout.create(3, 6, firstSeatId = 1).isEditOf(old))
        // Disjoint ids share nothing, so they are trivially consistent.
        assertTrue(SeatLayout.create(2, 6, firstSeatId = 13).isEditOf(old))
        // Swapping two seats' ids is not an edit.
        val swapped = SeatLayout(
            listOf(LayoutRow(listOf(LayoutCell.Seat(SeatId(2)), LayoutCell.Seat(SeatId(1))))),
            nextSeatId = 3,
        )
        assertFalse(swapped.isEditOf(layoutOf("SS")))
    }

    @Test
    fun createWithGapFillHasNoSeats() {
        val layout = SeatLayout.create(rows = 2, columns = 2, firstSeatId = 1, fill = CellType.GAP)
        assertEquals(0, layout.seatCount)
        assertEquals(1, layout.nextSeatId)
    }

    @Test
    fun fromPatternsParsesAllCellTypes() {
        val layout = layoutOf("S.|S", "SSSS")
        assertEquals(LayoutCell.Seat(SeatId(1)), layout.cell(0, 0))
        assertEquals(LayoutCell.Gap, layout.cell(0, 1))
        assertEquals(LayoutCell.Aisle, layout.cell(0, 2))
        assertEquals(LayoutCell.Seat(SeatId(2)), layout.cell(0, 3))
        assertEquals(ids(3, 4, 5, 6), layout.rows[1].seatIds)
        assertEquals("S.|S", LayoutPattern.encode(layout.rows[0].cells))
    }

    @Test
    fun patternRejectsUnknownCharsAndWrongIdCount() {
        assertFailsWith<IllegalArgumentException> { layoutOf("SX") }
        assertFailsWith<IllegalArgumentException> { LayoutPattern.decode("SS", ids(1)) }
    }

    @Test
    fun validationRejectsBrokenLayouts() {
        assertFailsWith<IllegalArgumentException> { SeatLayout(emptyList(), 1) }
        assertFailsWith<IllegalArgumentException> { SeatLayout(listOf(LayoutRow(emptyList())), 1) }
        // Ragged rows.
        assertFailsWith<IllegalArgumentException> { layoutOf("SS", "S") }
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
        val layout = layoutOf("SSS", "SSS")
        val edited = layout.insertRow(0)
        assertEquals(3, edited.rowCount)
        assertEquals(ids(7, 8, 9), edited.rows[0].seatIds)
        assertEquals(ids(1, 2, 3), edited.rows[1].seatIds)
        assertEquals(ids(4, 5, 6), edited.rows[2].seatIds)
        assertEquals(10, edited.nextSeatId)
    }

    @Test
    fun insertingColumnKeepsIdsAndAllocatesNewOnes() {
        val layout = layoutOf("SS", "SS")
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
        val layout = layoutOf("SSS", "SSS")
        assertEquals(ids(4, 5, 6).toSet(), layout.removeRow(0).seatIds)
        assertEquals(ids(1, 3, 4, 6).toSet(), layout.removeColumn(1).seatIds)
        assertFailsWith<IllegalArgumentException> { layoutOf("S").removeRow(0) }
        assertFailsWith<IllegalArgumentException> { layoutOf("S").removeColumn(0) }
    }

    @Test
    fun seatIdsAreNeverReusedAfterRemoval() {
        val layout = layoutOf("SS")
        val removed = layout.withCellType(0, 1, CellType.GAP)
        assertEquals(ids(1).toSet(), removed.seatIds)
        val redrawn = removed.withCellType(0, 1, CellType.SEAT)
        assertEquals(LayoutCell.Seat(SeatId(3)), redrawn.cell(0, 1))
        assertEquals(4, redrawn.nextSeatId)
    }

    @Test
    fun withCellTypeSameTypeIsNoOp() {
        val layout = layoutOf("SS")
        assertSame(layout, layout.withCellType(0, 0, CellType.SEAT))
    }

    @Test
    fun cycleCellGoesSeatGapAisleSeat() {
        var layout = layoutOf("S")
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
        val layout = layoutOf("SS|SS")
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
        val layout = layoutOf("SS", "SS")
        val grown = layout.resize(3, 3)
        assertEquals(3, grown.rowCount)
        assertEquals(3, grown.columnCount)
        assertEquals(CellPosition(0, 0), grown.positionOf(SeatId(1)))
        assertEquals(CellPosition(1, 1), grown.positionOf(SeatId(4)))
        val shrunk = grown.resize(1, 1)
        assertEquals(ids(1).toSet(), shrunk.seatIds)
        assertNull(shrunk.positionOf(SeatId(2)))
    }

    @Test
    fun rowsKeepTheirDirectionAndSeatsTheirFixedLabelsThroughEdits() {
        val layout = layoutOf("SSS", "SSW")
            .updateRow(1) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }
            .withFixedLabel(1, 2, "R")
        val edited = layout.insertRow(0).insertColumn(0).removeColumn(3).resize(4, 3)
        assertEquals(NumberingDirection.LEFT_TO_RIGHT, edited.rows[0].numberingDirection) // the new row
        assertEquals(NumberingDirection.RIGHT_TO_LEFT, edited.rows[2].numberingDirection)
        assertEquals(listOf<String?>(null, null, null), edited.rows[2].fixedLabels) // the R's column was removed
        val kept = layout.insertRow(0).insertColumn(0).removeRow(0)
        assertEquals(NumberingDirection.RIGHT_TO_LEFT, kept.rows[1].numberingDirection)
        assertEquals(listOf(null, null, null, "R"), kept.rows[1].fixedLabels)
        assertEquals(LayoutCell.Seat(SeatId(6), SeatKind.WHEELCHAIR, "R"), kept.cell(1, 3))
    }

    @Test
    fun canonicalIdsCompareStructureNotIds() {
        val seed = layoutOf("SS|S", "WSS.")
            .updateRow(1) { it.copy(label = "R1", numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }
            .withFixedLabel(1, 0, "R")
        val shifted = seed.withIdsShiftedBy(40)
        assertEquals(seed, shifted.withCanonicalSeatIds())
        assertTrue(shifted.hasSameStructureAs(seed))
        assertEquals(7, shifted.withCanonicalSeatIds().nextSeatId)
        // Any other difference counts.
        assertFalse(seed.hasSameStructureAs(seed.withFixedLabel(1, 0, null)))
        assertFalse(seed.hasSameStructureAs(seed.updateRow(1) { it.copy(numberingDirection = NumberingDirection.LEFT_TO_RIGHT) }))
        assertFalse(seed.hasSameStructureAs(seed.withSeatKind(0, 0, SeatKind.SOFA)))
        assertFalse(seed.hasSameStructureAs(seed.updateRow(0) { it.copy(blockStartNumbers = listOf(2)) }))
        assertFalse(seed.hasSameStructureAs(seed.withCellType(0, 3, CellType.AISLE)))
        // Block starts that mean nothing for the labels don't count: trailing nulls, entries beyond the blocks.
        val withStarts = seed.updateRow(0) { it.copy(blockStartNumbers = listOf(null, 4)) } // 2 blocks
        assertTrue(withStarts.hasSameStructureAs(withStarts.updateRow(0) { it.copy(blockStartNumbers = listOf(null, 4, null)) }))
        assertTrue(withStarts.hasSameStructureAs(withStarts.updateRow(0) { it.copy(blockStartNumbers = listOf(null, 4, 9, null)) }))
        assertTrue(seed.hasSameStructureAs(seed.updateRow(1) { it.copy(blockStartNumbers = listOf(null)) })) // 1 block
        assertTrue(seed.hasSameStructureAs(seed.updateRow(1) { it.copy(blockStartNumbers = listOf(null, 3)) }))
        assertFalse(withStarts.hasSameStructureAs(withStarts.updateRow(0) { it.copy(blockStartNumbers = listOf(null, 5)) }))
        assertFalse(withStarts.hasSameStructureAs(withStarts.updateRow(0) { it.copy(blockStartNumbers = listOf(1, 4)) }))
        assertEquals(listOf(null, 4), withStarts.updateRow(0) { it.copy(blockStartNumbers = listOf(null, 4, 7)) }
            .withCanonicalSeatIds().rows[0].blockStartNumbers)
        // A seat deleted and redrawn in the same place has a new id but the same structure.
        assertTrue(seed.hasSameStructureAs(seed.withCellType(0, 0, CellType.GAP).withCellType(0, 0, CellType.SEAT).withIdsShiftedBy(0)))
    }
}
