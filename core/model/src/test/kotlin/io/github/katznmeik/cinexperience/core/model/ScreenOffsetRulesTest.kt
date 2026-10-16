package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenOffsetRulesTest {

    @Test
    fun validOffsetsAreFiniteHalfStepsWithinTheMaximum() {
        for (valid in listOf(0f, 0.5f, -0.5f, 3f, -12.5f, 30f, -30f)) {
            assertTrue(ScreenOffsetRules.isValid(valid), "$valid")
        }
        for (invalid in listOf(0.25f, -1.3f, 30.5f, -31f, 1000f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFalse(ScreenOffsetRules.isValid(invalid), "$invalid")
        }
    }

    @Test
    fun maximumIsHalfTheWidestEditorGrid() {
        // SeatLayout.MAX_COLUMNS is 60: the centre can reach the edge of the widest grid.
        assertEquals(30f, ScreenOffsetRules.MAX_MAGNITUDE)
        assertEquals(30f, ScreenOffsetRules.maxMagnitude(60))
        assertEquals(30f, ScreenOffsetRules.maxMagnitude(500))
    }

    @Test
    fun perGridMaximumKeepsTheScreenCentreOverTheGrid() {
        assertEquals(8f, ScreenOffsetRules.maxMagnitude(16))
        assertEquals(7.5f, ScreenOffsetRules.maxMagnitude(15))
        assertEquals(0.5f, ScreenOffsetRules.maxMagnitude(1))
        assertEquals(0.5f, ScreenOffsetRules.maxMagnitude(0))
    }

    @Test
    fun snapRoundsToHalfStepsAndClamps() {
        assertEquals(1.5f, ScreenOffsetRules.snap(1.4f))
        assertEquals(-2f, ScreenOffsetRules.snap(-2.1f))
        assertEquals(30f, ScreenOffsetRules.snap(99f))
        assertEquals(-30f, ScreenOffsetRules.snap(Float.NEGATIVE_INFINITY))
        assertEquals(0f, ScreenOffsetRules.snap(Float.NaN))
        // Clamped to the grid when the column count is known.
        assertEquals(8f, ScreenOffsetRules.snap(12f, columns = 16))
        assertEquals(-7.5f, ScreenOffsetRules.snap(-9f, columns = 15))
        assertEquals(3.5f, ScreenOffsetRules.snap(3.6f, columns = 16))
        // Halfway values round away from the centre on both sides.
        assertEquals(1f, ScreenOffsetRules.snap(0.75f))
        assertEquals(-1f, ScreenOffsetRules.snap(-0.75f))
        assertEquals(0.5f, ScreenOffsetRules.snap(0.25f))
        assertEquals(-0.5f, ScreenOffsetRules.snap(-0.25f))
    }

    @Test
    fun snapNeverGivesNegativeZeroAndAlwaysAValidOffset() {
        // Float equality in data classes tells -0.0 from 0.0.
        assertEquals(0f.toRawBits(), ScreenOffsetRules.snap(-0.1f).toRawBits())
        assertEquals(0f.toRawBits(), ScreenOffsetRules.snap(-0f).toRawBits())
        for (value in listOf(-100f, -7.26f, -0.74f, 0.2f, 4.75f, 29.9f, 61f)) {
            assertTrue(ScreenOffsetRules.isValid(ScreenOffsetRules.snap(value)), "$value")
            assertTrue(ScreenOffsetRules.isValid(ScreenOffsetRules.snap(value, columns = 7)), "$value / 7")
        }
    }

    @Test
    fun newHallsAreCentredAndNewCinemasNoFavourites() {
        assertEquals(0f, Hall(cinemaId = 1, name = "Saal 1").screenOffset)
        assertFalse(Cinema(name = "Kino").isFavourite)
    }
}
