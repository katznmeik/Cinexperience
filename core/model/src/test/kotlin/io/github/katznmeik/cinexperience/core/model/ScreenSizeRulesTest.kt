package io.github.katznmeik.cinexperience.core.model

import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_COLUMNS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The screen size bounds that the editor, the repositories and the backup validator share. */
class ScreenSizeRulesTest {

    @Test
    fun theBoundsAreTheEditorsLargestValues() {
        assertEquals(120f, ScreenSizeRules.MAX_WIDTH)
        assertEquals(ScreenSizeRules.maxWidth(MAX_COLUMNS), ScreenSizeRules.MAX_WIDTH)
        assertEquals(15f, ScreenSizeRules.MAX_DISTANCE)
        assertEquals(2f, ScreenSizeRules.maxWidth(1))
        assertEquals(32f, ScreenSizeRules.maxWidth(16))
        // Every grid's widest screen is a valid width.
        for (columns in 1..MAX_COLUMNS) assertTrue(ScreenSizeRules.isValidWidth(ScreenSizeRules.maxWidth(columns)))
    }

    @Test
    fun aWidthIsPositiveAndAtMostTheMaximum() {
        for (width in listOf(null, 0.5f, ScreenSizeRules.MIN_WIDTH, 34f, 120f)) {
            assertTrue(ScreenSizeRules.isValidWidth(width), "$width")
        }
        for (width in listOf(0f, -0f, -1f, 120.5f, 3e38f, Float.MAX_VALUE, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(ScreenSizeRules.isValidWidth(width), "$width")
        }
    }

    @Test
    fun aDistanceIsWithinZeroAndTheMaximum() {
        for (distance in listOf(null, 0f, -0f, 2f, 15f)) assertTrue(ScreenSizeRules.isValidDistance(distance), "$distance")
        for (distance in listOf(-0.5f, 15.5f, 3e38f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertFalse(ScreenSizeRules.isValidDistance(distance), "$distance")
        }
    }
}
