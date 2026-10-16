package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMapGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A screen moved past the row labels widens the canvas's content, not the row
 * labels' touch area (N24): a tap under it, beside the labels, is a tap outside
 * (it deselects the screen) and selects no row.
 */
class ScreenOverhangHitTest {

    private val metrics = editorMetrics(Density(1f), labelWidth = 22f)
    private val canvasWidth = 388f

    @Test
    fun aTapUnderAScreenMovedLeftIsATapOutside() {
        // Six columns; the screen as wide as the grid, three seats to the left: from −55 to 53,
        // while the labels (with the padding) end at −30.
        val g = SeatMapGeometry(SeatLayout.fromPatterns(List(4) { "SSSSSS" }, firstSeatId = 1), metrics, 6f, 2f, screenOffset = -3f)
        val t = editorViewBounds(Size(canvasWidth, 300f), g, maxZoomScale = 3f, labelHeight = 34f, handleRoom = 17.5f).fitted()
        val left = t.toScreen(Offset(g.screenLeft, g.screenY))
        val right = t.toScreen(Offset(g.screenRight, g.screenY))
        val label = screenLabelRect((left.x + right.x) / 2f, left.y, Size(120f, 14f), gap = 9f, canvasWidth = canvasWidth, dp = 1f)
        fun hit(content: Offset) = hitTest(t.toScreen(content), g, t, handleTouchRadius = 24f, label = label, screenSelected = true)

        val labelColumn = g.leftLabelRect(2)
        val labelsEnd = labelColumn.left - metrics.padding
        check(g.screenLeft < labelsEnd - metrics.pitchX)
        assertEquals(CanvasHit.RowLabel(2), hit(labelColumn.center))
        assertEquals(CanvasHit.RowLabel(2), hit(Offset(labelsEnd + 1f, labelColumn.center.y)))
        assertEquals(CanvasHit.Empty, hit(Offset(labelsEnd - 1f, labelColumn.center.y)))
        assertEquals(CanvasHit.Empty, hit(Offset((g.screenLeft + labelsEnd) / 2f, labelColumn.center.y)))
    }
}
