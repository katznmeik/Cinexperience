package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.ui.theme.DarkCinexColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class SeatStateTextsTest {

    @Test
    fun seatDescriptionsNameTheState() {
        val label = SeatLabel("7", 10)
        assertEquals("Row 7, Seat 10, great, favourite", seatDescription(label, SeatVisual(SeatTone.GREAT, isFavourite = true)))
        assertEquals("Row 7, Seat 10, OK", seatDescription(label, SeatVisual(SeatTone.OK)))
        assertEquals("Row 7, Seat 10, avoid", seatDescription(label, SeatVisual(SeatTone.AVOID)))
        assertEquals("Row 7, Seat 10, no data", seatDescription(label, SeatVisual.Neutral))
        assertEquals(
            "Row 10, Seat 3, sofa, no data, favourite",
            seatDescription(SeatLabel("10", 3, SeatKind.SOFA), SeatVisual(isFavourite = true)),
        )
    }

    @Test
    fun toneFromSeatColour() {
        assertEquals(SeatTone.GREAT, SeatColour.GREEN.toSeatTone())
        assertEquals(SeatTone.OK, SeatColour.YELLOW.toSeatTone())
        assertEquals(SeatTone.AVOID, SeatColour.RED.toSeatTone())
        assertEquals(SeatTone.NO_DATA, null.toSeatTone())
    }

    @Test
    fun mapSummaryCountsTonesAndNamesTheFavourite() {
        val labels = SeatLayout.fromPatterns("SSSSS", firstSeatId = 1).labels()
        val visuals = mapOf(
            SeatId(1) to SeatVisual(SeatTone.GREAT),
            SeatId(2) to SeatVisual(SeatTone.GREAT, isFavourite = true),
            SeatId(3) to SeatVisual(SeatTone.AVOID),
        )
        assertEquals(
            "Seat colours: 2 great, 1 avoid, 2 no data. Favourite: Row 1, Seat 2.",
            seatStatesDescription(visuals, labels),
        )
        assertEquals("Seat colours: 5 no data.", seatStatesDescription(emptyMap(), labels))
    }

    /*
     * Colour-blind readability (spec §7): avoid carries a ×, the favourite a ★, and
     * the marks drawn on a seat must stand out from its colour. Great and OK differ
     * in lightness as well as hue.
     */

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    @Test
    fun marksOnSeatsHaveEnoughContrast() {
        val colors = DarkCinexColors
        // Stars and kind symbols on coloured seats are drawn in onPrimary (WCAG 1.4.11: 3:1).
        listOf(colors.seat.great, colors.seat.ok, colors.seat.avoid).forEach { seat ->
            assertTrue("mark on $seat", contrast(colors.onPrimary, seat) >= 3f)
        }
        // The × on an avoid seat is drawn in the surface colour.
        assertTrue(contrast(colors.surface, colors.seat.avoid) >= 3f)
        // Marks on grey "no data" seats are drawn in the text colour.
        assertTrue(contrast(colors.text, colors.seat.noData) >= 4.5f)
    }

    @Test
    fun greatAndOkDifferInLightness() {
        val seat = DarkCinexColors.seat
        assertTrue(contrast(seat.great, seat.ok) >= 1.5f)
        assertTrue(contrast(seat.ok, seat.avoid) >= 2.5f)
        assertTrue(contrast(seat.great, seat.noData) >= 3f)
    }
}
