package io.github.katznmeik.cinexperience.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.katznmeik.cinexperience.core.testing.NON_TEXT_CONTRAST
import io.github.katznmeik.cinexperience.core.testing.TEXT_CONTRAST
import io.github.katznmeik.cinexperience.core.testing.contrastRatio
import org.junit.Assert.assertEquals
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
    fun errorTextOnEveryBackground() {
        // Error labels and messages (text fields with isError), e.g. in dialogs on surfaceRaised.
        val scheme = c.toMaterialColorScheme()
        listOf("surfaceRaised" to c.surfaceRaised, "surface" to c.surface, "canvas" to c.canvas, "background" to c.background)
            .forEach { (bg, color) -> assertContrast("error on $bg", scheme.error, color, TEXT_CONTRAST) }
        assertContrast("onError on error", scheme.onError, scheme.error, TEXT_CONTRAST)
        assertContrast("onErrorContainer on errorContainer", scheme.onErrorContainer, scheme.errorContainer, TEXT_CONTRAST)
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

    /** The seat fills with the colour of the numbers and marks drawn on them. */
    private val seatFills = mapOf(
        "great" to (c.seat.great to c.seat.onGreat),
        "ok" to (c.seat.ok to c.seat.onOk),
        "avoid" to (c.seat.avoid to c.seat.onAvoid),
        "noData" to (c.seat.noData to c.seat.onNoData),
    )

    @Test
    fun seatNumbersOnEveryFill() {
        // Numbers are text: 4.5:1 on every fill, including the favourite (drawn on its
        // colour's fill) and "no data" (the number sits on the fill inside the rim).
        seatFills.forEach { (name, colors) ->
            val (fill, on) = colors
            assertContrast("number on $name", on, fill, TEXT_CONTRAST)
        }
    }

    @Test
    fun marksOnSeats() {
        // ×, ★ and the kind symbols use the same on-colours as the numbers.
        seatFills.forEach { (name, colors) ->
            val (fill, on) = colors
            assertContrast("mark on $name", on, fill, NON_TEXT_CONTRAST)
        }
        assertContrast("× badge (text) on surface", c.text, c.surface, NON_TEXT_CONTRAST)
        assertContrast("favourite rim (text) on surface", c.text, c.surface, NON_TEXT_CONTRAST)
        assertContrast("× badge (surface) on avoid", c.surface, c.seat.avoid, NON_TEXT_CONTRAST)
    }

    @Test
    fun editorSelectionOnTheCanvas() {
        // The selected screen in the layout editor (bar and handles), the one non-seat hue.
        assertContrast("editorSelection on canvas", c.editorSelection, c.canvas, NON_TEXT_CONTRAST)
        assertContrast("handle arrows on editorSelection", c.onEditorSelection, c.editorSelection, NON_TEXT_CONTRAST)
    }

    @Test
    fun ticketQrCodeIsBlackOnWhite() {
        // The ticket's QR code (spec §6.11): meaningful graphics (3:1), here pure black on pure white.
        assertContrast("ticketQrModule on ticketQrBackground", c.ticketQrModule, c.ticketQrBackground, NON_TEXT_CONTRAST)
        assertEquals(21.0, contrastRatio(c.ticketQrModule, c.ticketQrBackground), 0.001)
        // Dark modules on a light panel, as entrance scanners expect.
        assertTrue(c.ticketQrModule.luminance() < c.ticketQrBackground.luminance())
    }

    @Test
    fun seatColourButtonLabels() {
        // The override buttons on the seat card: dark labels on the seat colours.
        assertContrast("onPrimary on great", c.onPrimary, c.seat.great, TEXT_CONTRAST)
        assertContrast("onPrimary on ok", c.onPrimary, c.seat.ok, TEXT_CONTRAST)
        assertContrast("onPrimary on avoid", c.onPrimary, c.seat.avoid, TEXT_CONTRAST)
    }
}
