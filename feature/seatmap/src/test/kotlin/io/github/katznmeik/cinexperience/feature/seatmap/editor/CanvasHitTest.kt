package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.katznmeik.cinexperience.core.model.CellPosition
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.feature.seatmap.map.SeatMapGeometry
import io.github.katznmeik.cinexperience.feature.seatmap.map.SeatMapMetrics
import io.github.katznmeik.cinexperience.feature.seatmap.map.ViewBounds
import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasHitTest {

    /** Editor metrics at density 1. */
    private val metrics = SeatMapMetrics(
        seatSize = 15f,
        seatGap = 3f,
        rowGap = 3f,
        labelWidth = 22f,
        labelGap = 5f,
        screenTopSpace = 11f,
        minScreenGap = 0f,
        padding = 12f,
        rightLabels = false,
    )
    private val handleTouchRadius = 24f
    private val distanceLabelHeight = 34f

    /** A wide hall whose screen is close to row 0: the handles' touch circles reach into row 0. */
    private val geometry = SeatMapGeometry(
        SeatLayout.fromPatterns(List(3) { "S".repeat(30) }, firstSeatId = 1),
        metrics,
        screenWidth = 30f,
        screenDistance = 0.5f,
    )
    private val transform = ViewBounds.of(Size(388f, 300f), geometry.contentBounds, 2.5f, 3f).fitted()

    private fun hit(content: Offset) =
        hitTest(transform.toScreen(content), geometry, transform, handleTouchRadius, distanceLabelHeight)

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
    }

    @Test
    fun screenControlsStillWorkAboveTheGrid() {
        assertEquals(CanvasHit.WidthHandle(right = false), hit(Offset(geometry.screenLeft, geometry.screenY)))
        assertEquals(CanvasHit.WidthHandle(right = true), hit(Offset(geometry.screenRight, geometry.screenY - 2f)))
        assertEquals(CanvasHit.DistanceHandle, hit(Offset(geometry.screenCenterX, geometry.screenY)))
        assertEquals(CanvasHit.DistanceHandle, hit(Offset(geometry.screenCenterX, geometry.screenY - 4f)))
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
}
