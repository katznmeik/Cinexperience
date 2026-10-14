package io.github.katznmeik.cinexperience.core.seatmapui

import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatColourSource
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import io.github.katznmeik.cinexperience.core.model.SeatState
import io.github.katznmeik.cinexperience.core.model.ViewingAngle
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(listOf("1", "2", "3", "4"), choices[1].seats.map { it.shortText })
        assertEquals("Row 2, Seat 1, wheelchair space", choices[1].seats[0].label.description)
        assertEquals("Row 2, Seat 2, companion seat", choices[1].seats[1].label.description)
    }

    @Test
    fun aRightToLeftRowIsOfferedAsDrawnWithItsFixedLabel() {
        // Kant Kino, Saal 4, row 8: drawn left to right 6 5 4 3 2 1 R.
        val kant = SeatLayout.fromPatterns("SSSSSSW", firstSeatId = 1)
            .updateRow(0) { it.copy(label = "8", numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }
            .withFixedLabel(0, 6, "R")
        val row = seatChoices(kant).single()
        assertEquals(listOf("6", "5", "4", "3", "2", "1", "R"), row.seats.map { it.shortText })
        assertEquals("Row 8, Seat R, wheelchair space", row.descriptionOf(row.seats.last()))
        assertEquals(SeatId(7), seatChoices(kant).findSeat("8", "r"))
        assertEquals(SeatId(1), seatChoices(kant).findSeat("8", " 6 "))
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
        assertEquals(SeatId(1), choices.findSeat("A", "1")) // restart: the first "A 1" wins
        assertEquals(SeatId(2), choices.findSeat(" a ", "2")) // trimmed, any case
        assertEquals(SeatId(5), choices.findSeat("2", "1")) // the wheelchair space is seat 1
        assertEquals(SeatId(6), choices.findSeat("2", "2"))
        assertEquals(SeatId(8), choices.findSeat("2", "4"))
        assertNull(choices.findSeat("B", "1"))
        assertNull(choices.findSeat("2", "5"))
    }

    /** Kant Kino Saal 3: 11 double sofas, each a row without a name, numbered 1–22 across the room. */
    private val kantSaal3 = SampleLayouts.kantKinoSaal3

    @Test
    fun rowsWithoutANameAreOneGroupBySeatNumber() {
        val choices = seatChoices(kantSaal3)
        val group = choices.single()
        assertTrue(group.isUnnamed)
        assertNull(group.label)
        assertEquals(0, group.rowIndex)
        assertEquals("Seats", group.chipText)
        assertEquals("Seats without a row", group.chipDescription)
        assertEquals((1..22).map { "$it" }, group.seats.map { it.shortText })
        assertEquals(1, group.blocks.size)
        assertNull(group.blockCaption(0))
        assertEquals("Seat 12, sofa", group.descriptionOf(group.seats[11]))
        // Seat 12 is the right place of the front sofa, seat 1 the left place of the back one.
        assertEquals(SampleLayouts.kantKinoSaal3Seat12, group.seats[11].seatId)
        assertEquals(kantSaal3.rows[10].seatIds[0], group.seats[0].seatId)
    }

    @Test
    fun aHallMixingNamedAndUnnamedRowsKeepsTheNamedRows() {
        // Rows 1 and 2, then two unnamed sofas (5 6, 7 8 … numbered on), a spacer, row 4.
        val mixed = SeatLayout.fromPatterns("SSS", "SSS", "FF.", ".FF", "...", "SSS", firstSeatId = 1)
            .updateRow(2) { it.copy(label = "-", blockStartNumbers = listOf(3)) }
            .updateRow(3) { it.copy(label = "-") }
        val choices = seatChoices(mixed)
        assertEquals(listOf("1", "2", null, "5"), choices.map { it.label })
        assertEquals(listOf("1", "2", "Seats", "5"), choices.map { it.chipText })
        assertEquals(listOf(0, 1, 2, 5), choices.map { it.rowIndex })
        // By number: the second sofa's 1 2 before the first one's 3 4.
        assertEquals(listOf("1", "2", "3", "4"), choices[2].seats.map { it.shortText })
        assertEquals(listOf(9, 10, 7, 8).map(::SeatId), choices[2].seats.map { it.seatId })
        assertEquals("Seat 3, sofa", choices[2].descriptionOf(choices[2].seats[2]))
        assertEquals("Row 5, Seat 1", choices[3].descriptionOf(choices[3].seats[0]))
        // Finding a seat: by row, or without one ("-", blank or null) among the unnamed rows.
        assertEquals(SeatId(9), choices.findSeat(null, "1"))
        assertEquals(SeatId(9), choices.findSeat(" - ", "1"))
        assertEquals(SeatId(8), choices.findSeat("", "4"))
        assertEquals(SeatId(1), choices.findSeat("1", "1"))
        assertEquals(SeatId(12), choices.findSeat("5", "2"))
        assertNull(choices.findSeat(null, "5"))
        assertNull(choices.findSeat("3", "1")) // the unnamed rows are not "row 3"
        assertNull(seatChoices(layout).findSeat(null, "1")) // a hall without unnamed rows
    }

    @Test
    fun findSeatInKantKinoSaal3() {
        val choices = seatChoices(kantSaal3)
        assertEquals(SampleLayouts.kantKinoSaal3Seat12, choices.findSeat(null, "12"))
        assertEquals(SampleLayouts.kantKinoSaal3Seat12, choices.findSeat("-", " 12 "))
        assertNull(choices.findSeat(null, "23"))
    }

    @Test
    fun descriptionsLeaveOutRowsWithoutAName() {
        assertEquals(
            "Seat map of Saal 3: 11 rows, 22 seats, including 22 sofa places. Screen at the top.",
            seatMapDescription("Saal 3", kantSaal3.labels()),
        )
        val visuals = mapOf(SampleLayouts.kantKinoSaal3Seat12 to SeatVisual(SeatTone.GREAT, isFavourite = true))
        assertEquals(
            "Seat colours: 1 great, 21 no data. Favourite: Seat 12, sofa.",
            seatStatesDescription(visuals, kantSaal3.labels()),
        )
        assertEquals(
            "Seat 12, sofa, great, favourite",
            seatDescription(kantSaal3.labels().labelOf(SampleLayouts.kantKinoSaal3Seat12)!!, visuals.values.single()),
        )
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
