package io.github.katznmeik.cinexperience.core.model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeatColoursTest {

    private val hallId = 7L
    private fun ratings(vararg values: Int) = values.map(::Rating)
    private fun visit(seat: Int?, rating: Int?) = Visit(
        hallId = hallId,
        date = LocalDate.of(2026, 1, 1),
        seatId = seat?.let(::SeatId),
        seatRating = Rating.ofOrNull(rating),
    )

    @Test
    fun noRatingsMeansNoColour() {
        assertNull(seatColourForRatings(emptyList()))
    }

    @Test
    fun thresholdsAreInclusiveAtFourAndTwoPointFive() {
        assertEquals(SeatColour.GREEN, seatColourForRatings(ratings(4)))
        assertEquals(SeatColour.GREEN, seatColourForRatings(ratings(5, 3))) // 4.0
        assertEquals(SeatColour.YELLOW, seatColourForRatings(ratings(4, 4, 3))) // 3.67
        assertEquals(SeatColour.YELLOW, seatColourForRatings(ratings(2, 3))) // 2.5
        assertEquals(SeatColour.RED, seatColourForRatings(ratings(2, 3, 2))) // 2.33
        assertEquals(SeatColour.RED, seatColourForRatings(ratings(1)))
        assertEquals(SeatColour.GREEN, seatColourForRatings(ratings(5)))
    }

    @Test
    fun overrideTakesPrecedenceOverRatings() {
        assertEquals(SeatColour.RED to SeatColourSource.OVERRIDE, resolveSeatColour(SeatColour.RED, ratings(5, 5)))
        assertEquals(SeatColour.GREEN to SeatColourSource.OVERRIDE, resolveSeatColour(SeatColour.GREEN, emptyList()))
        assertEquals(SeatColour.YELLOW to SeatColourSource.RATINGS, resolveSeatColour(null, ratings(3)))
        assertEquals(null to SeatColourSource.NONE, resolveSeatColour(null, emptyList()))
    }

    @Test
    fun seatStatesCoverEverySeatAndAggregatePerSeat() {
        val layout = SeatLayout.fromPatterns("SSS")
        val states = seatStates(
            layout,
            visits = listOf(visit(1, 5), visit(1, 4), visit(1, null), visit(2, 1), visit(null, 3)),
            overrides = emptyList(),
        )
        assertEquals(layout.seatIds, states.keys)
        val first = states.getValue(SeatId(1))
        assertEquals(SeatColour.GREEN, first.colour)
        assertEquals(SeatColourSource.RATINGS, first.colourSource)
        assertEquals(4.5, first.averageRating)
        assertEquals(2, first.ratingCount)
        assertEquals(3, first.visitCount)
        assertEquals(SeatLabel("1", 1), first.label)

        assertEquals(SeatColour.RED, states.getValue(SeatId(2)).colour)
        val empty = states.getValue(SeatId(3))
        assertNull(empty.colour)
        assertEquals(SeatColourSource.NONE, empty.colourSource)
        assertNull(empty.averageRating)
        assertEquals(0, empty.visitCount)
        assertFalse(empty.isFavourite)
    }

    @Test
    fun seatStatesApplyOverridePrecedenceAndFavourite() {
        val layout = SeatLayout.fromPatterns("SS")
        val states = seatStates(
            layout,
            visits = listOf(visit(1, 5)),
            overrides = listOf(
                SeatOverride(hallId, SeatId(1), colour = SeatColour.RED),
                SeatOverride(hallId, SeatId(2), isFavourite = true),
            ),
        )
        val overridden = states.getValue(SeatId(1))
        assertEquals(SeatColour.RED, overridden.colour)
        assertEquals(SeatColourSource.OVERRIDE, overridden.colourSource)
        assertEquals(SeatColour.GREEN, overridden.automaticColour)
        assertEquals(SeatColour.RED, overridden.overrideColour)
        // A favourite without colour override still uses the automatic colour (here: none).
        val favourite = states.getValue(SeatId(2))
        assertTrue(favourite.isFavourite)
        assertNull(favourite.colour)
    }

    @Test
    fun seatStatesIgnoreDataOfSeatsNotInLayout() {
        val states = seatStates(
            SeatLayout.fromPatterns("S"),
            visits = listOf(visit(9, 1)),
            overrides = listOf(SeatOverride(hallId, SeatId(9), isFavourite = true)),
        )
        assertEquals(setOf(SeatId(1)), states.keys)
        assertFalse(states.getValue(SeatId(1)).isFavourite)
    }

    @Test
    fun withFavouriteKeepsAtMostOneFavourite() {
        var overrides = SeatOverrides.withFavourite(emptyList(), hallId, SeatId(1))
        assertEquals(listOf(SeatOverride(hallId, SeatId(1), isFavourite = true)), overrides)

        overrides = SeatOverrides.withColour(overrides, hallId, SeatId(2), SeatColour.YELLOW)
        overrides = SeatOverrides.withFavourite(overrides, hallId, SeatId(2))
        assertEquals(1, overrides.count { it.isFavourite })
        assertEquals(SeatId(2), SeatOverrides.favourite(overrides))
        // Seat 1 had only the favourite flag, so its override disappears.
        assertEquals(listOf(SeatOverride(hallId, SeatId(2), SeatColour.YELLOW, isFavourite = true)), overrides)

        overrides = SeatOverrides.withFavourite(overrides, hallId, null)
        assertNull(SeatOverrides.favourite(overrides))
        assertEquals(listOf(SeatOverride(hallId, SeatId(2), SeatColour.YELLOW)), overrides)
    }

    @Test
    fun withFavouriteRepairsSeveralStoredFavourites() {
        val corrupt = listOf(
            SeatOverride(hallId, SeatId(3), isFavourite = true),
            SeatOverride(hallId, SeatId(1), isFavourite = true),
        )
        assertEquals(SeatId(1), SeatOverrides.favourite(corrupt))
        val fixed = SeatOverrides.withFavourite(corrupt, hallId, SeatId(5))
        assertEquals(listOf(SeatOverride(hallId, SeatId(5), isFavourite = true)), fixed)
    }

    @Test
    fun withColourSetsAndClearsAndDropsEmptyOverrides() {
        var overrides = SeatOverrides.withColour(emptyList(), hallId, SeatId(1), SeatColour.GREEN)
        assertEquals(listOf(SeatOverride(hallId, SeatId(1), SeatColour.GREEN)), overrides)
        overrides = SeatOverrides.withColour(overrides, hallId, SeatId(1), SeatColour.RED)
        assertEquals(listOf(SeatOverride(hallId, SeatId(1), SeatColour.RED)), overrides)
        overrides = SeatOverrides.withColour(overrides, hallId, SeatId(1), null)
        assertTrue(overrides.isEmpty())
    }

    @Test
    fun clearingColourKeepsFavourite() {
        var overrides = SeatOverrides.withFavourite(emptyList(), hallId, SeatId(1))
        overrides = SeatOverrides.withColour(overrides, hallId, SeatId(1), SeatColour.RED)
        overrides = SeatOverrides.withColour(overrides, hallId, SeatId(1), null)
        assertEquals(listOf(SeatOverride(hallId, SeatId(1), isFavourite = true)), overrides)
    }

    @Test
    fun clearedRemovesOneSeatOnly() {
        val overrides = listOf(
            SeatOverride(hallId, SeatId(1), SeatColour.RED, isFavourite = true),
            SeatOverride(hallId, SeatId(2), SeatColour.GREEN),
        )
        assertEquals(listOf(overrides[1]), SeatOverrides.cleared(overrides, hallId, SeatId(1)))
    }

    @Test
    fun storageKeysRoundTrip() {
        SeatColour.entries.forEach { assertEquals(it, SeatColour.fromStorageKey(it.storageKey)) }
        ScreeningFormat.entries.forEach { assertEquals(it, ScreeningFormat.fromStorageKey(it.storageKey)) }
    }
}
