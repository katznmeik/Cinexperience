package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import io.github.katznmeik.cinexperience.core.testing.NON_TEXT_CONTRAST
import io.github.katznmeik.cinexperience.core.testing.TEXT_CONTRAST
import io.github.katznmeik.cinexperience.core.testing.contrastRatio
import io.github.katznmeik.cinexperience.core.ui.theme.DarkCinexColors
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's marks reach 3:1 and its texts 4.5:1 on what they are drawn on:
 * gap dashes and aisle hatching on the canvas, seat numbers on the translucent
 * seats, and over a reference screenshot (anything from black to white, at the
 * highest opacity) the numbers' halo and the screen's outline and label backdrop.
 */
class EditorContrastTest {

    private val c = DarkCinexColors

    /** The canvas under a reference screenshot of [color] at the highest opacity. */
    private fun underReference(color: Color) =
        color.copy(alpha = LayoutEditorUiState.MAX_REFERENCE_OPACITY).compositeOver(c.canvas)

    private val referenceExtremes = listOf(Color.White, Color.Black, Color(0xFF808080))

    private fun assertContrast(name: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$name: %.2f:1 is below %.1f:1".format(ratio, minimum), ratio >= minimum)
    }

    @Test
    fun gapDashesOnTheCanvas() {
        val dash = c.textMuted.copy(alpha = EDITOR_MARK_ALPHA).compositeOver(c.canvas)
        assertContrast("gap dashes", dash, c.canvas, NON_TEXT_CONTRAST)
    }

    @Test
    fun aisleHatchingOnTheAisleFill() {
        val aisle = c.surface.copy(alpha = 0.6f).compositeOver(c.canvas)
        val hatch = c.textMuted.copy(alpha = EDITOR_MARK_ALPHA).compositeOver(aisle)
        assertContrast("aisle hatching", hatch, aisle, NON_TEXT_CONTRAST)
    }

    @Test
    fun seatNumbersOnTheEditorSeats() {
        val seat = c.text.copy(alpha = EDITOR_SEAT_FILL_ALPHA).compositeOver(c.canvas)
        assertContrast("number on an editor seat", c.text, seat, TEXT_CONTRAST)
    }

    @Test
    fun seatNumbersOverAReferenceScreenshotHaveAHalo() {
        // Without the halo, a light screenshot would swallow the light numbers ...
        val seatOverWhite = c.text.copy(alpha = EDITOR_SEAT_FILL_ALPHA).compositeOver(underReference(Color.White))
        assertTrue(contrastRatio(c.text, seatOverWhite) < TEXT_CONTRAST)
        // ... with it, the digits sit on the opaque canvas colour, whatever lies beneath.
        assertContrast("number on its halo", c.text, c.canvas, TEXT_CONTRAST)
    }

    @Test
    fun theScreenOverAReferenceScreenshot() {
        referenceExtremes.forEach { reference ->
            val below = underReference(reference)
            val backdrop = c.canvas.copy(alpha = 0.85f).compositeOver(below)
            val label = c.text.copy(alpha = 0.8f).compositeOver(backdrop)
            assertContrast("screen label over $reference", label, backdrop, TEXT_CONTRAST)
        }
        // The bar and the handles have an opaque canvas-coloured outline.
        assertContrast("selected bar on its outline", c.editorSelection, c.canvas, NON_TEXT_CONTRAST)
        assertContrast("bar on its outline", c.text, c.canvas, NON_TEXT_CONTRAST)
        assertContrast("handle arrows", c.onEditorSelection, c.editorSelection, NON_TEXT_CONTRAST)
    }

    @Test
    fun theHintUnderTheCanvas() {
        val hint = c.text.copy(alpha = 0.75f).compositeOver(c.canvas)
        assertContrast("hint", hint, c.canvas, TEXT_CONTRAST)
    }
}
