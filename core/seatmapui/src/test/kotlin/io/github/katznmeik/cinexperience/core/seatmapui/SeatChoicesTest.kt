package io.github.katznmeik.cinexperience.core.seatmapui

import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatColourSource
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import io.github.katznmeik.cinexperience.core.model.SeatState
import io.github.katznmeik.cinexperience.core.model.ViewingAngle
import io.github.katznmeik.cinexperience.core.model.labels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeatChoicesTest {

    private val layout = SeatLayout.fromPatterns("SS|SS", "||||.", "WC|SF", firstSeatId = 1)
        .updateRow(0) { it.copy(label = "A", numbering = SeatNumbering.RESTART_PER_BLOCK) }

    @Test
    fun choicesAreGroupedByRowWithoutSeatlessRows() {
        val choices = seatChoices(layout)
        assertEquals(listOf("A", "2"), choices.map { it.label })
        assertEquals(listOf(0, 2), choices.map { it.rowIndex })
        assertEquals(listOf("1", "2", "1", "2"), choices[0].seats.map { it.shortText })
        assertEquals(listOf(0, 0, 1, 1), choices[0].seats.map { it.block })
        assertEquals(listOf("W1", "1", "2", "3"), choices[1].seats.map { it.shortText })
        assertEquals("Row 2, Seat 1, companion seat", choices[1].seats[1].label.description)
    }

    @Test
    fun splitRowsNameTheirBlocks() {
        val choices = seatChoices(layout)
        val rowA = choices[0]
        assertEquals(2, rowA.blocks.size)
        assertEquals(listOf("Left", "Right"), rowA.blocks.indices.map { rowA.blockCaption(it) })
        assertEquals("Row A, Seat 1, left block", rowA.descriptionOf(rowA.seats[0]))
        assertEquals("Row A, Seat 1, right block", rowA.descriptionOf(rowA.seats[2]))

        val three = seatChoices(SeatLayout.fromPatterns("S|S|S", firstSeatId = 1))[0]
        assertEquals(listOf("Left", "Centre", "Right"), three.blocks.indices.map { three.blockCaption(it) })
        val four = seatChoices(SeatLayout.fromPatterns("S|S|S|S", firstSeatId = 1))[0]
        assertEquals("Block 4", four.blockCaption(3))

        val single = seatChoices(SeatLayout.fromPatterns("SSS", firstSeatId = 1))[0]
        assertNull(single.blockCaption(0))
        assertEquals("Row 1, Seat 2", single.descriptionOf(single.seats[1]))
    }

    @Test
    fun findSeatByRowAndNumber() {
        val choices = seatChoices(layout.labels())
        assertEquals(SeatId(1), choices.findSeat("A", 1)) // restart: the first "A 1" wins
        assertEquals(SeatId(2), choices.findSeat(" a ", 2)) // trimmed, any case
        assertEquals(SeatId(5), choices.findSeat("2", 1, SeatKind.WHEELCHAIR))
        assertEquals(SeatId(6), choices.findSeat("2", 1))
        assertEquals(SeatId(8), choices.findSeat("2", 3, SeatKind.SOFA))
        assertNull(choices.findSeat("B", 1))
        assertNull(choices.findSeat("2", 2, SeatKind.WHEELCHAIR))
    }

    @Test
    fun descriptionNamesHallRowsSeatsAndSpecialSeats() {
        assertEquals(
            "Seat map of Saal 1: 2 rows, 8 seats, including 1 wheelchair space, 1 companion seat, 1 sofa place. " +
                "Screen at the top.",
            seatMapDescription("Saal 1", layout.labels()),
        )
        assertEquals(
            "Seat map of Saal 2: 1 row, 3 seats. Screen at the top.",
            seatMapDescription("Saal 2", SeatLayout.fromPatterns("SSS", firstSeatId = 1).labels()),
        )
        assertEquals("≈ 38° viewing angle", viewingAngleText(ViewingAngle(37.6, 4.0)))
    }

    @Test
    fun seatStatesMapToVisuals() {
        fun state(colour: SeatColour?, favourite: Boolean) = SeatState(
            SeatId(1), SeatLabel("1", 1), colour, SeatColourSource.NONE, null, null, null, 0, 0, favourite,
        )
        assertEquals(SeatVisual.Neutral, state(null, favourite = false).toSeatVisual())
        assertEquals(SeatVisual(SeatTone.GREAT, isFavourite = true), state(SeatColour.GREEN, favourite = true).toSeatVisual())
        assertEquals(SeatTone.OK, SeatColour.YELLOW.toSeatTone())
        assertEquals(SeatTone.AVOID, SeatColour.RED.toSeatTone())
    }
}
