package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.ui.geometry.Offset
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The row label areas end with the label columns (and the drawing's padding beside
 * them), not with the content (N24): a screen that sticks out past the labels, moved
 * sideways or wider than the hall, widens the content, but the room under it is no
 * row label.
 */
class RowLabelAreaTest {

    /** pitchX = 12, pitchY = 14; labels 8 wide, 2 from the grid: left labels from −10 to −2. */
    private val metrics = SeatMapMetrics(
        seatSize = 10f,
        seatGap = 2f,
        rowGap = 4f,
        labelWidth = 8f,
        labelGap = 2f,
        screenTopSpace = 5f,
        minScreenGap = 0f,
        padding = 1f,
    )

    /** Cells 58 wide (grid 64 with the staggered row): right labels from 66 to 74. */
    private val layout = SeatLayout.fromPatterns("SS|SS", "S.|SS", firstSeatId = 1)
        .updateRow(1) { it.copy(halfSeatOffset = true) }

    @Test
    fun aScreenMovedLeftDoesNotWidenTheLeftLabels() {
        val g = SeatMapGeometry(layout, metrics, 4f, 2f, screenOffset = -3f) // from −31 to 17
        assertEquals(-32f, g.contentBounds.left)
        // The label column, out to the padding.
        assertEquals(0, g.rowLabelAt(Offset(-5f, 5f)))
        assertEquals(1, g.rowLabelAt(Offset(-10.5f, 19f)))
        // Under the screen's overhang, left of the labels: no row.
        assertNull(g.rowLabelAt(Offset(-11.5f, 5f)))
        assertNull(g.rowLabelAt(Offset(-25f, 19f)))
    }

    @Test
    fun aScreenMovedRightDoesNotWidenTheRightLabels() {
        val g = SeatMapGeometry(layout, metrics, 4f, 2f, screenOffset = 3f) // from 41 to 89
        assertEquals(90f, g.contentBounds.right)
        assertEquals(0, g.rowLabelAt(Offset(70f, 5f)))
        assertEquals(1, g.rowLabelAt(Offset(74.5f, 19f)))
        assertNull(g.rowLabelAt(Offset(75.5f, 5f)))
        assertNull(g.rowLabelAt(Offset(85f, 19f)))

        // The seat map's "SCREEN" caption widens the content too, not the labels.
        val captioned = SeatMapGeometry(layout, metrics.copy(screenCaptionWidth = 80f), 4f, 2f, screenOffset = 2f)
        assertEquals(94f, captioned.contentBounds.right)
        assertNull(captioned.rowLabelAt(Offset(80f, 5f)))
    }

    @Test
    fun aScreenWiderThanTheHallDoesNotWidenTheLabels() {
        val g = SeatMapGeometry(layout, metrics, 10f, 2f) // from −31 to 89
        assertEquals(-32f, g.contentBounds.left)
        assertEquals(0, g.rowLabelAt(Offset(-5f, 5f)))
        assertNull(g.rowLabelAt(Offset(-20f, 5f)))
        assertEquals(0, g.rowLabelAt(Offset(70f, 5f)))
        assertNull(g.rowLabelAt(Offset(80f, 5f)))
    }

    @Test
    fun withoutRightLabelsOnlyTheLeftOnesCount() {
        val g = SeatMapGeometry(layout, metrics.copy(rightLabels = false), 4f, 2f, screenOffset = 3f)
        assertNull(g.rowLabelAt(Offset(70f, 5f)))
        assertEquals(0, g.rowLabelAt(Offset(-5f, 5f)))
    }
}
