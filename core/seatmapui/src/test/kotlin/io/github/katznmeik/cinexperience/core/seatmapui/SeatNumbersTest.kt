package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatNumbersTest {

    @Test
    fun numberedKindsShowTheirNumberWheelchairSpacesNone() {
        assertEquals("10", SeatLabel("7", 10).seatNumberText)
        assertEquals("3", SeatLabel("7", 3, SeatKind.COMPANION).seatNumberText)
        assertEquals("12", SeatLabel("7", 12, SeatKind.SOFA).seatNumberText)
        assertNull(SeatLabel("1", 2, SeatKind.WHEELCHAIR).seatNumberText)
    }

    @Test
    fun everyNumberedSeatOfALayoutGetsItsLabelsNumber() {
        // W C . | . F F | S W: wheelchair spaces are skipped, companion and sofa places counted.
        val layout = SeatLayout.fromPatterns("WC.|.FF|SW", firstSeatId = 1)
        val numbers = layout.labels().seatNumbers()
        assertEquals(mapOf(SeatId(2) to "1", SeatId(3) to "2", SeatId(4) to "3", SeatId(5) to "4"), numbers)
        assertNull(numbers[SeatId(1)])
        assertNull(numbers[SeatId(6)])
    }

    @Test
    fun numbersFollowTheRowsNumberingAndStartNumbers() {
        val layout = SeatLayout.fromPatterns("SS|SS", firstSeatId = 1)
        assertEquals(listOf("1", "2", "3", "4"), layout.labels().seatNumbers().values.toList())
        val restarted = layout.updateRow(0) { it.copy(numbering = SeatNumbering.RESTART_PER_BLOCK) }
        assertEquals(listOf("1", "2", "1", "2"), restarted.labels().seatNumbers().values.toList())
        val started = layout.updateRow(0) { it.copy(blockStartNumbers = listOf(101, 21)) }
        assertEquals(listOf("101", "102", "21", "22"), started.labels().seatNumbers().values.toList())
    }

    @Test
    fun theMarksOfANumberedSeat() {
        assertEquals(emptyList<SeatMark>(), seatMarks(SeatKind.STANDARD, SeatVisual(SeatTone.GREAT)))
        assertEquals(listOf(SeatMark.AVOID), seatMarks(SeatKind.STANDARD, SeatVisual(SeatTone.AVOID)))
        assertEquals(listOf(SeatMark.FAVOURITE), seatMarks(SeatKind.STANDARD, SeatVisual(SeatTone.OK, isFavourite = true)))
        assertEquals(listOf(SeatMark.KIND), seatMarks(SeatKind.SOFA, SeatVisual.Neutral))
        assertEquals(
            listOf(SeatMark.KIND, SeatMark.AVOID, SeatMark.FAVOURITE),
            seatMarks(SeatKind.COMPANION, SeatVisual(SeatTone.AVOID, isFavourite = true)),
        )
    }

    @Test
    fun marksSitSideBySideInTheBandAboveTheNumber() {
        val seat = Rect(0f, 0f, 100f, 100f)
        val one = marksBand(seat, 1).single()
        assertEquals(50f, one.center.x, 0.01f) // centred
        assertEquals(38f, one.width, 0.01f)
        val three = marksBand(seat, 3)
        assertEquals(3, three.size)
        three.zipWithNext { a, b -> assertTrue("side by side", a.right <= b.left) }
        for (count in 1..3) {
            marksBand(seat, count).forEach { box ->
                assertTrue("inside the seat: $box", box.left >= 0f && box.right <= 100f && box.top >= 0f)
                // Above the number: its digits start at 69 % − 0.72 × 54 % / 2 ≈ 49.6 % of the seat.
                assertTrue("above the number: $box", box.bottom <= 49f)
            }
        }
        assertEquals(emptyList<Rect>(), marksBand(seat, 0))
    }

    @Test
    fun numbersAppearFromAMinimumSizeOnScreen() {
        val density = Density(3f)
        val seatSize = 16f * 3f // a 16 dp seat at zoom 1
        val minScale = seatNumbersMinScale(seatSize, density)
        // 7 dp digits need the 16 dp seat at 7 / (16 × 0.54) ≈ 0.81 of its size.
        assertEquals(7f / (16f * SEAT_NUMBER_SIZE), minScale, 0.001f)
    }

    @Test
    fun aTwentyTwoColumnHallShowsItsNumbersOnAPhone() {
        val density = Density(3f)
        // The hall detail's map: 412 dp minus the card's margins and padding.
        val width = (412f - 2 * 12f - 2 * 6f) * 3f
        assertEquals(22, SampleLayouts.splitHall.columnCount)
        assertTrue(seatNumbersShownWhenFitted(SampleLayouts.splitHall, width, density, screenWidth = 16f))
        assertTrue(seatNumbersShownWhenFitted(SeatLayout.fromPatterns(List(8) { "S".repeat(24) }, firstSeatId = 1), width, density))
        // Wider halls show them once zoomed in.
        assertFalse(seatNumbersShownWhenFitted(SeatLayout.fromPatterns(List(8) { "S".repeat(26) }, firstSeatId = 1), width, density))
        // A screen moved far past the side widens the drawing as well.
        val twenty = SeatLayout.fromPatterns(List(8) { "S".repeat(20) }, firstSeatId = 1)
        assertTrue(seatNumbersShownWhenFitted(twenty, width, density, screenWidth = 16f))
        assertFalse(seatNumbersShownWhenFitted(twenty, width, density, screenWidth = 16f, screenOffset = -10f))
    }
}
