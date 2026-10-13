package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SeatKindTest {

    private fun SeatLayout.label(id: Int): SeatLabel = labels().labelOf(SeatId(id))!!

    @Test
    fun patternCharactersRoundTrip() {
        val layout = layoutOf("SWCF.|S")
        val cells = layout.rows[0].cells
        assertEquals(
            listOf(SeatKind.STANDARD, SeatKind.WHEELCHAIR, SeatKind.COMPANION, SeatKind.SOFA),
            cells.filterIsInstance<LayoutCell.Seat>().take(4).map { it.kind },
        )
        assertEquals(5, layout.seatCount)
        assertEquals(5, LayoutPattern.seatCount("SWCF.|S"))
        assertEquals("SWCF.|S", LayoutPattern.encode(cells))
        assertFailsWith<IllegalArgumentException> { layoutOf("SX") }
        assertFailsWith<IllegalArgumentException> { layoutOf("s") } // case matters
    }

    @Test
    fun changingTheKindKeepsTheSeatId() {
        val layout = layoutOf("SSS")
        val edited = layout.withSeatKind(0, 1, SeatKind.SOFA)
        assertEquals(LayoutCell.Seat(SeatId(2), SeatKind.SOFA), edited.cell(0, 1))
        assertEquals(layout.seatIds, edited.seatIds)
        assertEquals(layout.nextSeatId, edited.nextSeatId)
        assertTrue(edited.isEditOf(layout))
        assertSame(edited, edited.withSeatKind(0, 1, SeatKind.SOFA))
        assertFailsWith<IllegalArgumentException> { layoutOf("S.").withSeatKind(0, 1, SeatKind.SOFA) }
        // Shifting ids (seed layouts) keeps kinds.
        assertEquals(LayoutCell.Seat(SeatId(12), SeatKind.SOFA), edited.withIdsShiftedBy(10).cell(0, 1))
    }

    @Test
    fun sessionSetSeatKeepsIdsAndRestoresGhosts() {
        val original = layoutOf("SSS") // ids 1..3
        var session = LayoutEditSession.start(original, hallNextSeatId = 4)
        session = session.setSeat(0, 0, SeatKind.WHEELCHAIR)
        assertEquals(LayoutCell.Seat(SeatId(1), SeatKind.WHEELCHAIR), session.layout!!.cell(0, 0))
        assertTrue(session.hasChanges)
        assertTrue(session.removedSeatsWithData(original.seatIds).isEmpty())

        // A seat removed and painted back as a companion seat gets its id back.
        session = session.setCellType(0, 2, CellType.AISLE).setSeat(0, 2, SeatKind.COMPANION)
        assertEquals(LayoutCell.Seat(SeatId(3), SeatKind.COMPANION), session.layout!!.cell(0, 2))

        // Painting the same kind again is a no-op; painting a new cell allocates a fresh id.
        assertSame(session, session.setSeat(0, 2, SeatKind.COMPANION))
        val grown = session.insertColumn(3, fill = CellType.GAP).setSeat(0, 3, SeatKind.SOFA)
        assertEquals(LayoutCell.Seat(SeatId(4), SeatKind.SOFA), grown.layout!!.cell(0, 3))

        // Back to standard: no change left.
        val reverted = session.setSeat(0, 0, SeatKind.STANDARD).setSeat(0, 2, SeatKind.STANDARD)
        assertFalse(reverted.hasChanges)
    }

    @Test
    fun wheelchairSpacesAreNotCountedInSeatNumbers() {
        val layout = layoutOf("WSSWSC|FSW")
        // Seats: W(1) S(2) S(3) W(4) S(5) C(6) | F(7) S(8) W(9), continuous numbering.
        assertEquals(SeatLabel("1", 1, SeatKind.WHEELCHAIR), layout.label(1))
        assertEquals(SeatLabel("1", 1), layout.label(2))
        assertEquals(SeatLabel("1", 2), layout.label(3))
        assertEquals(SeatLabel("1", 2, SeatKind.WHEELCHAIR), layout.label(4))
        assertEquals(SeatLabel("1", 3), layout.label(5))
        assertEquals(SeatLabel("1", 4, SeatKind.COMPANION), layout.label(6))
        assertEquals(SeatLabel("1", 5, SeatKind.SOFA), layout.label(7))
        assertEquals(SeatLabel("1", 6), layout.label(8))
        assertEquals(SeatLabel("1", 3, SeatKind.WHEELCHAIR), layout.label(9))
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 1, 1, 1), layout.labels().seats.values.map { it.block })
    }

    @Test
    fun wheelchairOnlyBlocksDoNotShiftNumbering() {
        // Continuous: the first numbered seat of the row is 1 even after a wheelchair-only block.
        val continuous = layoutOf("WW|SS|SS")
        assertEquals(listOf(1, 2, 1, 2, 3, 4), continuous.labels().seats.values.map { it.label.number })
        // Restart per block with a block start: the start applies to the first numbered seat.
        val restart = layoutOf("SW|WSS").updateRow(0) {
            it.copy(numbering = SeatNumbering.RESTART_PER_BLOCK, blockStartNumbers = listOf(null, 10))
        }
        assertEquals(listOf(1, 1, 2, 10, 11), restart.labels().seats.values.map { it.label.number })
        // A row of wheelchair spaces only is still a seat row.
        assertEquals(listOf("1", "2"), layoutOf("WW", "SS").labels().rowLabels)
    }

    @Test
    fun displayTextsNameTheKind() {
        assertEquals("Row 1, Wheelchair 2", SeatLabel("1", 2, SeatKind.WHEELCHAIR).displayText)
        assertEquals("Row 1, Wheelchair 2", SeatLabel("1", 2, SeatKind.WHEELCHAIR).description)
        assertEquals("Row F, Seat 12", SeatLabel("F", 12, SeatKind.COMPANION).displayText)
        assertEquals("Row F, Seat 12, companion seat", SeatLabel("F", 12, SeatKind.COMPANION).description)
        assertEquals("Row F, Seat 3, sofa", SeatLabel("F", 3, SeatKind.SOFA).description)
        assertEquals("Row F, Seat 3", SeatLabel("F", 3).description)
    }
}
