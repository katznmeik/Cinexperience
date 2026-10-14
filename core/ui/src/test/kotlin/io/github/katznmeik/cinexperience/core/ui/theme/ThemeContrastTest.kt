package io.github.katznmeik.cinexperience.core.ui.theme

import androidx.compose.ui.graphics.Color
import io.github.katznmeik.cinexperience.core.testing.NON_TEXT_CONTRAST
import io.github.katznmeik.cinexperience.core.testing.TEXT_CONTRAST
import io.github.katznmeik.cinexperience.core.testing.contrastRatio
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour tokens meet WCAG AA contrast where they carry meaning: text 4.5:1,
 * seat shapes, seat marks and outline-only controls 3:1. Decorative tokens
 * ([CinexColors.divider], [CinexColors.outline] around labelled controls) and
 * disabled states have no minimum.
 */
class ThemeContrastTest {

    private val c = DarkCinexColors
    private val backgrounds = mapOf(
        "background" to c.background,
        "surface" to c.surface,
        "surfaceRaised" to c.surfaceRaised,
        "canvas" to c.canvas,
        "navigationBar" to c.navigationBar,
    )

    private fun assertContrast(name: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$name: %.2f:1 is below %.1f:1".format(ratio, minimum), ratio >= minimum)
    }

    @Test
    fun textOnEveryBackground() {
        backgrounds.forEach { (bg, color) ->
            assertContrast("text on $bg", c.text, color, TEXT_CONTRAST)
            assertContrast("textMuted on $bg", c.textMuted, color, TEXT_CONTRAST)
        }
    }

    @Test
    fun textOnLightFills() {
        // Selected chips, the "Add visit" button, Save, the selected rankings scope.
        assertContrast("onPrimary on text", c.onPrimary, c.text, TEXT_CONTRAST)
    }

    @Test
    fun outlineOnlyControls() {
        backgrounds.forEach { (bg, color) -> assertContrast("fieldOutline on $bg", c.fieldOutline, color, NON_TEXT_CONTRAST) }
    }

    @Test
    fun seatShapesOnTheMapCardAndBackground() {
        for ((bg, color) in listOf("surface" to c.surface, "background" to c.background)) {
            assertContrast("great on $bg", c.seat.great, color, NON_TEXT_CONTRAST)
            assertContrast("ok on $bg", c.seat.ok, color, NON_TEXT_CONTRAST)
            assertContrast("avoid on $bg", c.seat.avoid, color, NON_TEXT_CONTRAST)
            // The dark "no data" fill stays subtle; its rim carries the shape.
            assertContrast("noDataOutline on $bg", c.seat.noDataOutline, color, NON_TEXT_CONTRAST)
        }
    }

    @Test
    fun marksOnSeats() {
        assertContrast("kind symbol (text) on noData", c.text, c.seat.noData, NON_TEXT_CONTRAST)
        assertContrast("star (onPrimary) on great", c.onPrimary, c.seat.great, NON_TEXT_CONTRAST)
        assertContrast("star (onPrimary) on ok", c.onPrimary, c.seat.ok, NON_TEXT_CONTRAST)
        assertContrast("star (onPrimary) on avoid", c.onPrimary, c.seat.avoid, NON_TEXT_CONTRAST)
        assertContrast("avoid × (surface) on avoid", c.surface, c.seat.avoid, NON_TEXT_CONTRAST)
        assertContrast("× badge (text) on surface", c.text, c.surface, NON_TEXT_CONTRAST)
        assertContrast("favourite rim (text) on surface", c.text, c.surface, NON_TEXT_CONTRAST)
    }

    @Test
    fun seatColourButtonLabels() {
        // The override buttons on the seat card: dark labels on the seat colours.
        assertContrast("onPrimary on great", c.onPrimary, c.seat.great, TEXT_CONTRAST)
        assertContrast("onPrimary on ok", c.onPrimary, c.seat.ok, TEXT_CONTRAST)
        assertContrast("onPrimary on avoid", c.onPrimary, c.seat.avoid, TEXT_CONTRAST)
    }
}
