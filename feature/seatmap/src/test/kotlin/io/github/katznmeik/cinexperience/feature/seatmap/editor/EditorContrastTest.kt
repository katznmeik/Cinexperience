package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.ui.graphics.compositeOver
import io.github.katznmeik.cinexperience.core.testing.NON_TEXT_CONTRAST
import io.github.katznmeik.cinexperience.core.testing.contrastRatio
import io.github.katznmeik.cinexperience.core.ui.theme.DarkCinexColors
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor's gap dashes and aisle hatching reach 3:1 on the canvas (non-text contrast). */
class EditorContrastTest {

    private val c = DarkCinexColors

    @Test
    fun gapDashesOnTheCanvas() {
        val dash = c.textMuted.copy(alpha = EDITOR_MARK_ALPHA).compositeOver(c.canvas)
        val ratio = contrastRatio(dash, c.canvas)
        assertTrue("gap dashes: %.2f:1".format(ratio), ratio >= NON_TEXT_CONTRAST)
    }

    @Test
    fun aisleHatchingOnTheAisleFill() {
        val aisle = c.surface.copy(alpha = 0.6f).compositeOver(c.canvas)
        val hatch = c.textMuted.copy(alpha = EDITOR_MARK_ALPHA).compositeOver(aisle)
        val ratio = contrastRatio(hatch, aisle)
        assertTrue("aisle hatching: %.2f:1".format(ratio), ratio >= NON_TEXT_CONTRAST)
    }
}
