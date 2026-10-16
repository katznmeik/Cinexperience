package io.github.katznmeik.cinexperience.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextLimitsTest {

    /** U+1F3AC CLAPPER BOARD: one code point, two chars. */
    private val emoji = "\uD83C\uDFAC"

    @Test
    fun limits() {
        assertEquals(1_000, TextLimits.MAX_NAME_LENGTH)
        assertEquals(20_000, TextLimits.MAX_LONG_TEXT_LENGTH)
    }

    @Test
    fun textsUpToTheLimitFit() {
        assertTrue(TextLimits.fits(null, 3))
        assertTrue(TextLimits.fits("", 3))
        assertTrue(TextLimits.fits("abc", 3))
        assertFalse(TextLimits.fits("abcd", 3))
        assertTrue(TextLimits.fits("a".repeat(TextLimits.MAX_NAME_LENGTH), TextLimits.MAX_NAME_LENGTH))
        assertFalse(TextLimits.fits("a".repeat(TextLimits.MAX_NAME_LENGTH + 1), TextLimits.MAX_NAME_LENGTH))
    }

    @Test
    fun codePointsCountOnce() {
        assertTrue(TextLimits.fits(emoji.repeat(3), 3))
        assertFalse(TextLimits.fits(emoji.repeat(4), 3))
        assertTrue(TextLimits.fits("ab$emoji", 3))
        assertFalse(TextLimits.fits("abc$emoji", 3))
        assertTrue(TextLimits.fits(emoji.repeat(TextLimits.MAX_NAME_LENGTH), TextLimits.MAX_NAME_LENGTH))
        assertFalse(TextLimits.fits(emoji.repeat(TextLimits.MAX_NAME_LENGTH) + "a", TextLimits.MAX_NAME_LENGTH))
        // A lone surrogate is one code point.
        assertTrue(TextLimits.fits("\uD83Cab", 3))
    }
}
