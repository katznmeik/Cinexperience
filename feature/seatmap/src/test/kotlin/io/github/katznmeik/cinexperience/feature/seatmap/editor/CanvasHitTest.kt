package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.katznmeik.cinexperience.core.model.CellPosition
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMapGeometry
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMapMetrics
import io.github.katznmeik.cinexperience.core.seatmapui.ViewBounds
import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasHitTest {

    /** Editor metrics at density 1. */
    private val metrics = SeatMapMetrics(
        seatSize = 16f,
        seatGap = 2f,
        rowGap = 3f,
        labelWidth = 22f,
        labelGap = 4f,
        screenTopSpace = 34f,
        minScreenGap = 0f,
        padding = 4f,
        rightLabels = false,
        screenSideSpace = 13.5f,
    )
    private val handleTouchRadius = 24f
    private val labelHeight = 34f

    /** A wide hall whose screen is close to row 0: the handles' touch circles reach into row 0. */
    private val geometry = SeatMapGeometry(
        SeatLayout.fromPatterns(List(3) { "S".repeat(30) }, firstSeatId = 1),
        metrics,
        screenWidth = 30f,
        screenDistance = 0.5f,
    )
    private val transform = ViewBounds.of(Size(388f, 300f), geometry.contentBounds, 2.5f, 3f).fitted()

    private fun hit(content: Offset, selected: Boolean = true, g: SeatMapGeometry = geometry) =
        hitTest(transform.toScreen(content), g, transform, handleTouchRadius, labelHeight, screenSelected = selected)

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
        val t = ViewBounds.of(Size(388f, 300f), g.contentBounds, 2.5f, 3f).fitted()
        fun at(content: Offset, selected: Boolean = false) =
            hitTest(t.toScreen(content), g, t, handleTouchRadius, labelHeight, screenSelected = selected)
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
        val t = ViewBounds.of(Size(388f, 300f), g.contentBounds, 2.5f, 3f).fitted()
        fun at(content: Offset, selected: Boolean = true) =
            hitTest(t.toScreen(content), g, t, handleTouchRadius, labelHeight, screenSelected = selected)
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
