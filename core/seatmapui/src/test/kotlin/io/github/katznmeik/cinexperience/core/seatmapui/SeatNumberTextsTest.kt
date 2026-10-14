package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Seat numbers measured with the app's number font (Instrument Sans, semibold). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class SeatNumberTextsTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** A 16 dp seat at density 3. */
    private val seatSize = 48f

    private fun numberTexts(): SeatNumberTexts {
        lateinit var texts: SeatNumberTexts
        composeRule.setContent {
            CinexTheme {
                val measurer = rememberTextMeasurer()
                val style = SeatNumberTexts.style(MaterialTheme.typography.labelSmall)
                val density = LocalDensity.current
                texts = remember { SeatNumberTexts(measurer, style, seatSize, density) }
            }
        }
        composeRule.waitForIdle()
        return texts
    }

    @Test
    fun longNumbersShrinkByTheirNumberOfDigits() {
        val texts = numberTexts()
        val standard = texts.standardFontSize
        // Up to two digits: the standard size.
        for (number in listOf("1", "7", "10", "11", "88")) assertEquals(number, standard, texts[number].fontSize, 0.001f)

        // Three digits: one smaller size for all of them, narrow ("111") or wide ("888").
        val three = texts["100"].fontSize
        assertTrue("$three < $standard", three < standard)
        for (number in listOf("101", "111", "250", "888")) assertEquals(number, three, texts[number].fontSize, 0.001f)
        assertEquals(three, texts.fontSizeFor(3), 0.001f)

        // Four digits: smaller still, again the same for all.
        val four = texts["1000"].fontSize
        assertTrue("$four < $three", four < three)
        assertEquals(four, texts["9999"].fontSize, 0.001f)

        // Every number fits the seat (within a pixel of rounding).
        for (number in listOf("88", "100", "111", "888", "1000", "9999")) {
            val width = texts[number].layout.size.width
            assertTrue("$number: $width px", width <= seatSize * SEAT_NUMBER_MAX_WIDTH + 1f)
        }
    }
}
