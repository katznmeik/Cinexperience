package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.LayoutPattern
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The column stepper (`withColumnCount`) after painting over a seat at the
 * right edge: one step down removes the column, one step up re-inserts it with
 * the cell types of the column left of it. The removed seat must not come back
 * with its id or its fixed label.
 */
class LayoutDraftColumnCountTest {

    // Row 2: seats 4, 5, 6 ("R") with 6 at the right edge.
    private val stored = SeatLayout.fromPatterns("SSS", "SSS", firstSeatId = 1).withFixedLabel(1, 2, "R")
    private val hall = Hall(id = 1, cinemaId = 1, name = "Saal 1", layout = stored, screenWidth = 3f, screenDistance = 1.5f, nextSeatId = MARK)

    private fun LayoutDraft.seat(row: Int, column: Int) = layout.cell(row, column) as LayoutCell.Seat

    @Test
    fun steppingTheColumnsDownAndUpAfterPaintingOverASeatGivesNewSeats() {
        val painted = LayoutDraft.start(hall).paint(1, 2, CellType.GAP)
        val narrower = painted.withColumnCount(2)
        assertEquals(2, narrower.columnCount)

        val wider = narrower.withColumnCount(3)
        assertEquals(listOf("SSS", "SSS"), wider.layout.rows.map { LayoutPattern.encode(it.cells) })
        for (row in 0 until 2) {
            val seat = wider.seat(row, 2)
            assertTrue("row $row: new id, got ${seat.id}", seat.id.value >= MARK)
            assertNull(seat.fixedLabel)
        }
        assertFalse(SeatId(6) in wider.layout.seatIds)
        assertFalse(SeatId(3) in wider.layout.seatIds)
        assertEquals(listOf(SeatId(4), SeatId(5)), wider.layout.rows[1].seatIds.take(2))
        assertEquals(
            setOf(SeatId(3), SeatId(6)),
            wider.session.removedSeatsWithData(stored.seatIds).map { it.seatId }.toSet(),
        )

        // Painting the new seat over and back keeps the new seat, not seat 6.
        val repainted = wider.paint(1, 2, CellType.GAP).paint(1, 2, CellType.SEAT)
        assertTrue(repainted.seat(1, 2).id.value >= MARK)
        assertNull(repainted.seat(1, 2).fixedLabel)
    }

    @Test
    fun steppingAGapColumnBackPaintsNewSeatsLater() {
        // Column 1 becomes gaps, so the re-inserted right column is gaps too.
        val painted = LayoutDraft.start(hall)
            .paint(0, 1, CellType.GAP)
            .paint(1, 1, CellType.GAP)
            .paint(1, 2, CellType.GAP)
        val wider = painted.withColumnCount(2).withColumnCount(3)
        assertEquals(LayoutCell.Gap, wider.layout.cell(1, 2))

        val seat = wider.paint(1, 2, CellType.SEAT).seat(1, 2)
        assertTrue("new id, got ${seat.id}", seat.id.value >= MARK)
        assertNull(seat.fixedLabel)
        // Seat 5, painted over in a column that stayed, still comes back as itself.
        assertEquals(LayoutCell.Seat(SeatId(5)), wider.paint(1, 1, CellType.SEAT).seat(1, 1))
    }

    private companion object {
        const val MARK = 20
    }
}
