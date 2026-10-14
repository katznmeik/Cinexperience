package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import io.github.katznmeik.cinexperience.core.model.CellPosition
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMapGeometry
import io.github.katznmeik.cinexperience.core.seatmapui.ViewTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasHitTest {

    /** Editor metrics at density 1 (see [editorMetrics]). */
    private val metrics = editorMetrics(Density(1f), labelWidth = 22f)
    private val handleTouchRadius = 24f
    private val labelHeight = 34f
    private val canvasWidth = 388f

    /** A wide hall whose screen is close to row 0: the handles' touch circles reach into row 0. */
    private val geometry = SeatMapGeometry(
        SeatLayout.fromPatterns(List(3) { "S".repeat(30) }, firstSeatId = 1),
        metrics,
        screenWidth = 30f,
        screenDistance = 0.5f,
    )
    private val transform = fitted(geometry)

    private fun fitted(g: SeatMapGeometry) =
        editorViewBounds(Size(canvasWidth, 300f), g, maxZoomScale = 3f, labelHeight = labelHeight, handleRoom = 17.5f).fitted()

    /**
     * The label's backdrop where the canvas draws it: about "SCREEN · tap to adjust"
     * (unselected) or "↕ distance 2.0 rows · 3 seats left" (selected) at density 1.
     */
    private fun label(g: SeatMapGeometry, t: ViewTransform, selected: Boolean): Rect {
        val left = t.toScreen(Offset(g.screenLeft, g.screenY))
        val right = t.toScreen(Offset(g.screenRight, g.screenY))
        val size = if (selected) Size(200f, 22f) else Size(120f, 14f)
        return screenLabelRect((left.x + right.x) / 2f, left.y, size, gap = if (selected) 8f else 9f, canvasWidth = canvasWidth, dp = 1f)
    }

    private fun hitAt(point: Offset, g: SeatMapGeometry, t: ViewTransform, selected: Boolean) =
        hitTest(point, g, t, handleTouchRadius, label(g, t, selected), screenSelected = selected)

    private fun hit(content: Offset, selected: Boolean = true, g: SeatMapGeometry = geometry) =
        hitAt(transform.toScreen(content), g, transform, selected)

    @Test
    fun rowZeroIsPaintedEvenUnderTheScreenHandles() {
        val firstCell = geometry.cellCenter(0, 0)
        val handle = Offset(geometry.screenLeft, geometry.screenY)
        // The handle's touch circle covers the first cell's centre ...
        check((transform.toScreen(firstCell) - transform.toScreen(handle)).getDistance() <= handleTouchRadius)
        // ... but cells win below the top of row 0.
        assertEquals(CanvasHit.Cell(CellPosition(0, 0)), hit(firstCell))
        assertEquals(CanvasHit.Cell(CellPosition(0, 29)), hit(geometry.cellCenter(0, 29)))
        assertEquals(CanvasHit.Cell(CellPosition(0, 15)), hit(geometry.cellCenter(0, 15))) // under the bar's middle
        assertEquals(CanvasHit.Cell(CellPosition(0, 15)), hit(Offset(geometry.cellCenter(0, 15).x, -1f))) // row 0's gap half
        assertEquals(CanvasHit.Cell(CellPosition(0, 0)), hit(firstCell, selected = false))
    }

    @Test
    fun theSelectedScreenHasHandlesAndABar() {
        assertEquals(CanvasHit.WidthHandle(right = false), hit(Offset(geometry.screenLeft, geometry.screenY)))
        assertEquals(CanvasHit.WidthHandle(right = true), hit(Offset(geometry.screenRight, geometry.screenY - 2f)))
        assertEquals(CanvasHit.ScreenBar, hit(Offset(geometry.screenCenterX, geometry.screenY)))
        assertEquals(CanvasHit.ScreenBar, hit(Offset(geometry.screenCenterX, geometry.screenY - 4f)))
    }

    @Test
    fun anUnselectedScreenIsOneBarWithoutHandles() {
        // Where the handles would be, a touch selects (and drags) the bar instead of resizing.
        assertEquals(CanvasHit.ScreenBar, hit(Offset(geometry.screenLeft, geometry.screenY), selected = false))
        assertEquals(CanvasHit.ScreenBar, hit(Offset(geometry.screenRight, geometry.screenY - 2f), selected = false))
        assertEquals(CanvasHit.ScreenBar, hit(Offset(geometry.screenCenterX, geometry.screenY), selected = false))
    }

    @Test
    fun theStraightBarIsHitAlongItsLineOnly() {
        // A short screen far from the grid: the bar is a straight line at screenY.
        val g = SeatMapGeometry(SeatLayout.fromPatterns(List(3) { "S".repeat(30) }, firstSeatId = 1), metrics, 10f, 6f)
        val t = fitted(g)
        fun at(content: Offset, selected: Boolean = false) = hitAt(t.toScreen(content), g, t, selected)
        val y = g.screenY
        assertEquals(CanvasHit.ScreenBar, at(Offset(g.screenLeft + 1f, y)))
        assertEquals(CanvasHit.ScreenBar, at(Offset(g.screenRight - 1f, y)))
        // The label above the bar's middle belongs to it.
        assertEquals(CanvasHit.ScreenBar, at(Offset(g.screenCenterX, y - 30f)))
        // The same height beside the bar, below it, and far above it: nothing.
        assertEquals(CanvasHit.Empty, at(Offset(g.screenLeft - 40f, y)))
        assertEquals(CanvasHit.Empty, at(Offset(g.screenRight + 40f, y)))
        assertEquals(CanvasHit.Empty, at(Offset(g.screenCenterX, y + 40f)))
        assertEquals(CanvasHit.Empty, at(Offset(g.screenCenterX, y - 80f)))
        // Selected: handles at both ends.
        assertEquals(CanvasHit.WidthHandle(right = false), at(Offset(g.screenLeft, y), selected = true))
        assertEquals(CanvasHit.WidthHandle(right = true), at(Offset(g.screenRight, y), selected = true))
    }

    @Test
    fun anOffsetScreenHasItsHandlesAtItsShiftedEnds() {
        // A 10-seat screen 6 seats to the left of a 30-column hall's centre, close to row 0.
        val g = SeatMapGeometry(
            SeatLayout.fromPatterns(List(3) { "S".repeat(30) }, firstSeatId = 1),
            metrics,
            screenWidth = 10f,
            screenDistance = 0.5f,
            screenOffset = -6f,
        )
        val t = fitted(g)
        fun at(content: Offset, selected: Boolean = true) = hitAt(t.toScreen(content), g, t, selected)
        assertEquals(g.cellsWidth / 2f - 6f * metrics.pitchX, g.screenCenterX)
        assertEquals(CanvasHit.WidthHandle(right = false), at(Offset(g.screenLeft, g.screenY)))
        assertEquals(CanvasHit.WidthHandle(right = true), at(Offset(g.screenRight, g.screenY)))
        assertEquals(CanvasHit.ScreenBar, at(Offset(g.screenCenterX, g.screenY)))
        // Where a centred screen's middle would be: beside the shifted bar, nothing.
        assertEquals(CanvasHit.Empty, at(Offset(g.cellsWidth / 2f + 3f * metrics.pitchX, g.screenY), selected = false))
        // Row 0 under the shifted handles still paints.
        assertEquals(CanvasHit.Cell(CellPosition(0, 4)), at(g.cellCenter(0, 4)))
        assertEquals(CanvasHit.Cell(CellPosition(0, 13)), at(g.cellCenter(0, 13)))
    }

    @Test
    fun theLabelIsTouchedWhereItIsDrawn() {
        // A two-seat screen, zoomed in so far that its centre is 20 px from the canvas's left edge.
        val g = SeatMapGeometry(SeatLayout.fromPatterns(List(3) { "S".repeat(30) }, firstSeatId = 1), metrics, 2f, 6f)
        val scale = 3f
        val barY = 80f
        val t = ViewTransform(scale, Offset(20f - g.screenCenterX * scale, barY - g.screenY * scale))
        // The label is kept inside the canvas, right of the bar's centre ...
        val drawn = label(g, t, selected = false)
        assertEquals(6f - 3f, drawn.left, 0.01f)
        // ... and touched there, well past the bar's end, from its top down to the bar.
        val pastTheBar = t.toScreen(Offset(g.screenRight, g.screenY)).x + 30f
        assertTrue(pastTheBar < drawn.right)
        assertEquals(CanvasHit.ScreenBar, hitAt(Offset(pastTheBar, drawn.center.y), g, t, selected = false))
        assertEquals(CanvasHit.ScreenBar, hitAt(Offset(pastTheBar, drawn.top + 1f), g, t, selected = false))
        assertEquals(CanvasHit.ScreenBar, hitAt(Offset(pastTheBar, barY - 1f), g, t, selected = false))
        // Beside it and above it: nothing.
        assertEquals(CanvasHit.Empty, hitAt(Offset(drawn.right + 10f, drawn.center.y), g, t, selected = false))
        assertEquals(CanvasHit.Empty, hitAt(Offset(pastTheBar, drawn.top - 2f), g, t, selected = false))

        // The selected label is wider (about 200 px): touched all along it.
        val tf = fitted(g)
        val selected = label(g, tf, selected = true)
        assertTrue(selected.width > 200f)
        for (x in listOf(selected.left + 2f, selected.center.x, selected.right - 2f)) {
            assertEquals("x = $x", CanvasHit.ScreenBar, hitAt(Offset(x, selected.center.y), g, tf, selected = true))
        }
    }

    @Test
    fun aShortSelectedScreenKeepsAGrabbableMiddle() {
        // Two seats wide: the handles' touch circles (24 px) would cover the whole bar.
        val g = SeatMapGeometry(SeatLayout.fromPatterns(List(3) { "S".repeat(30) }, firstSeatId = 1), metrics, 2f, 3f)
        val t = fitted(g)
        val left = t.toScreen(Offset(g.screenLeft, g.screenY))
        val right = t.toScreen(Offset(g.screenRight, g.screenY))
        val centre = (left + right) / 2f
        check((centre - left).getDistance() < handleTouchRadius)
        // The middle moves the bar ...
        assertEquals(CanvasHit.ScreenBar, hitAt(centre, g, t, selected = true))
        assertEquals(CanvasHit.ScreenBar, hitAt(centre + Offset(5f, 3f), g, t, selected = true))
        assertEquals(CanvasHit.ScreenBar, hitAt(centre - Offset(5f, 0f), g, t, selected = true))
        // ... the ends and beyond resize it.
        assertEquals(CanvasHit.WidthHandle(right = true), hitAt(right, g, t, selected = true))
        assertEquals(CanvasHit.WidthHandle(right = true), hitAt(right + Offset(15f, 0f), g, t, selected = true))
        assertEquals(CanvasHit.WidthHandle(right = false), hitAt(left, g, t, selected = true))
        assertEquals(CanvasHit.WidthHandle(right = false), hitAt(left - Offset(15f, -5f), g, t, selected = true))
        // A long screen's handles are unchanged.
        assertEquals(CanvasHit.WidthHandle(right = false), hit(Offset(geometry.screenLeft + 10f, geometry.screenY)))
    }

    /**
     * Spec §6.5: the label above the screen and the handles on its ends are fully
     * visible (so they can be touched) at the fitted zoom however wide the hall is,
     * selected or not, also with the screen as wide as the grid or wider, or moved to
     * its side; at density 3 on a phone.
     */
    @Test
    fun theScreenLabelAndHandlesFitWideHallsAtTheFittedZoom() {
        val density = 3f
        val m = editorMetrics(Density(density), labelWidth = 22f * density)
        val width = 388f * density
        val handle = 13.5f * density // radius with its ring
        for (columns in listOf(1, 4, 16, 24, 30, 40, 60)) {
            val layout = SeatLayout.fromPatterns(List(10) { "S".repeat(columns) }, firstSeatId = 1)
            val screens = listOf(columns to 0f, 2 * columns to 0f, columns / 2 to columns / 2f, columns / 2 to -columns / 2f)
            for ((screenWidth, offset) in screens) {
                val g = SeatMapGeometry(layout, m, screenWidth.toFloat(), 2f, offset)
                val view = editorViewBounds(Size(width, 900f), g, maxZoomScale = 3f, labelHeight = 34f * density, handleRoom = 17.5f * density)
                val t = view.fitted()
                val case = "$columns columns, screen $screenWidth at $offset"
                // The grid stays as big as the canvas allows: the handles take room only where needed.
                assertTrue(case, view.fitScale <= width / g.contentBounds.width + 0.001f)
                val left = t.toScreen(Offset(g.screenLeft, g.screenY))
                val right = t.toScreen(Offset(g.screenRight, g.screenY))
                assertTrue("$case: handles at ${left.x}, ${right.x}", left.x - handle >= 0f && right.x + handle <= width)
                assertTrue("$case: bar at ${left.y}", left.y - handle >= 0f)
                for (selected in listOf(false, true)) {
                    val size = if (selected) Size(200f * density, 22f * density) else Size(130f * density, 15f * density)
                    val gap = (if (selected) 8f else 9f) * density
                    val rect = screenLabelRect((left.x + right.x) / 2f, left.y, size, gap, width, density)
                    assertTrue("$case, selected $selected: $rect", rect.top >= 0f && rect.left >= 0f && rect.right <= width)
                }
            }
        }
    }

    @Test
    fun theLabelInvitesATapOnlyWhereATapSelectsTheScreen() {
        assertEquals("SCREEN · tap to adjust", screenLabelText(selected = false, tappable = true, distance = 2f, offset = 0f))
        assertEquals("SCREEN", screenLabelText(selected = false, tappable = false, distance = 2f, offset = 0f))
        assertEquals("distance 2.5 rows · 3 seats left", screenLabelText(selected = true, tappable = true, distance = 2.5f, offset = -3f))
    }

    @Test
    fun aBarDragLocksToTheDirectionItStartsIn() {
        val slop = 8f
        assertEquals(null, dragAxis(Offset(3f, 4f), slop)) // within the slop: undecided
        assertEquals(DragAxis.HORIZONTAL, dragAxis(Offset(12f, 5f), slop))
        assertEquals(DragAxis.HORIZONTAL, dragAxis(Offset(-9f, 9f), slop)) // a tie moves sideways
        assertEquals(DragAxis.VERTICAL, dragAxis(Offset(4f, -11f), slop))
    }

    @Test
    fun rowLabelsAndEmptySpace() {
        assertEquals(CanvasHit.RowLabel(1), hit(geometry.leftLabelRect(1).center))
        assertEquals(CanvasHit.Empty, hit(Offset(geometry.cellsWidth + 6f, geometry.rowTop(2) + 4f)))
    }

    @Test
    fun referenceModeNeverPaints() {
        assertEquals(CanvasMode.EDIT, canvasMode(referenceMode = false, hasReferenceImage = true))
        assertEquals(CanvasMode.ALIGN_REFERENCE, canvasMode(referenceMode = true, hasReferenceImage = true))
        // The screenshot may still be loading (or failed to load): only zoom and pan.
        assertEquals(CanvasMode.VIEW_ONLY, canvasMode(referenceMode = true, hasReferenceImage = false))
    }

    @Test
    fun theHintFollowsTheMode() {
        assertEquals("Tap or drag to paint · tap the screen to adjust it", canvasHint(false, false, 0.4f, screenSelected = false))
        assertEquals("Drag the green handles to resize · drag the bar to move", canvasHint(false, true, 0.4f, screenSelected = true))
        assertEquals("Reference screenshot · 40 % opacity · drag to align", canvasHint(true, true, 0.4f, screenSelected = false))
    }
}
