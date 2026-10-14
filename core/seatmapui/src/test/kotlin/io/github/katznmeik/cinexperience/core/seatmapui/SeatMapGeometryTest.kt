package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.katznmeik.cinexperience.core.model.CellPosition
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeatMapGeometryTest {

    /** pitchX = 12, pitchY = 14. */
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

    // Ids: row 0 = 1, 2 | 3, 4; row 1 (staggered by half a seat) = 5, gap | 6, 7.
    private val layout = SeatLayout.fromPatterns("SS|SS", "S.|SS", firstSeatId = 1)
        .updateRow(1) { it.copy(halfSeatOffset = true) }

    private fun geometry(width: Float? = 4f, distance: Float? = 2f, m: SeatMapMetrics = metrics) =
        SeatMapGeometry(layout, m, width, distance)

    @Test
    fun cellsAreLaidOutOnThePitchWithHalfOffsetRows() {
        val g = geometry()
        assertEquals(Rect(0f, 0f, 10f, 10f), g.cellRect(0, 0))
        assertEquals(Rect(36f, 0f, 46f, 10f), g.cellRect(0, 3))
        assertEquals(Rect(6f, 14f, 16f, 24f), g.cellRect(1, 0)) // shifted by pitchX / 2
        assertEquals(58f, g.cellsWidth) // 5 × 12 − 2
        assertEquals(64f, g.gridWidth) // + half a pitch for the staggered row
        assertEquals(24f, g.gridHeight)
        assertEquals(Offset(11f, 19f), g.seatCenter(SeatId(5)))
        assertEquals(Offset(53f, 5f), g.seatCenter(SeatId(4)))
        assertNull(g.seatCenter(SeatId(99)))
    }

    @Test
    fun labelsSitOnBothSides() {
        val g = geometry()
        assertEquals(Rect(-10f, 14f, -2f, 24f), g.leftLabelRect(1))
        assertEquals(Rect(66f, 0f, 74f, 10f), g.rightLabelRect(0))
        assertNull(geometry(m = metrics.copy(rightLabels = false)).rightLabelRect(0))
    }

    @Test
    fun screenIsCentredOverTheCellsAtItsDistance() {
        val g = geometry(width = 4f, distance = 2f)
        assertEquals(29f, g.screenCenterX)
        assertEquals(5f, g.screenLeft) // 29 − 4 × 12 / 2
        assertEquals(53f, g.screenRight)
        assertEquals(-28f, g.screenY) // 2 rows × 14
        // Content: labels to the grid's edges, room above the screen, padding all round.
        assertEquals(Rect(-11f, -34f, 75f, 25f), g.contentBounds)

        // Defaults: as wide as the grid, 2 rows away; a minimum gap leaves room for a caption.
        val defaults = geometry(width = null, distance = null, m = metrics.copy(minScreenGap = 40f))
        assertEquals(5f, defaults.screenWidth)
        assertEquals(2f, defaults.screenDistance)
        assertEquals(-40f, defaults.screenY)

        // A screen wider than the hall widens the content.
        val wide = geometry(width = 10f)
        assertEquals(-32f, wide.contentBounds.left)
        assertEquals(90f, wide.contentBounds.right)

        // Distance 0 puts the screen on row 0 (editor metrics without a minimum gap).
        assertEquals(0f, geometry(distance = 0f).screenY)
    }

    @Test
    fun theStraightScreenMovesSidewaysByItsOffset() {
        // Centred: 29 ± 24. One and a half seats to the left (towards column 0): 18 px.
        val left = SeatMapGeometry(layout, metrics, 4f, 2f, screenOffset = -1.5f)
        assertEquals(11f, left.screenCenterX)
        assertEquals(-13f, left.screenLeft)
        assertEquals(35f, left.screenRight)
        assertEquals(-28f, left.screenY) // the distance is unchanged
        // The screen sticks out past the labels on the left: the content grows with it.
        assertEquals(-14f, left.contentBounds.left)
        assertEquals(75f, left.contentBounds.right)

        val right = SeatMapGeometry(layout, metrics, 4f, 2f, screenOffset = 2f)
        assertEquals(53f, right.screenCenterX)
        assertEquals(29f, right.screenLeft)
        assertEquals(77f, right.screenRight)
        assertEquals(78f, right.contentBounds.right)

        // A caption centred on the screen (the seat map's "SCREEN") stays inside the content: 53 + 60 / 2 + 1.
        val captioned = SeatMapGeometry(layout, metrics.copy(screenCaptionWidth = 60f), 4f, 2f, screenOffset = 2f)
        assertEquals(84f, captioned.contentBounds.right)
        assertEquals(-11f, captioned.contentBounds.left) // the labels reach further left

        // An unusable offset draws the screen centred.
        assertEquals(29f, SeatMapGeometry(layout, metrics, 4f, 2f, screenOffset = Float.NaN).screenCenterX)
    }

    @Test
    fun cellAtCoversTheGapsBetweenCells() {
        val g = geometry()
        assertEquals(CellPosition(0, 0), g.cellAt(Offset(5f, 5f)))
        assertEquals(CellPosition(0, 0), g.cellAt(Offset(10.9f, 11.9f))) // right / lower gap halves
        assertEquals(CellPosition(0, 1), g.cellAt(Offset(11.1f, 5f)))
        assertEquals(CellPosition(1, 0), g.cellAt(Offset(12.1f, 12.1f)))
        assertEquals(CellPosition(1, 0), g.cellAt(Offset(5f, 20f))) // left of the shifted seat: still column 0
        assertEquals(CellPosition(1, 4), g.cellAt(Offset(60f, 20f))) // the staggered row reaches further right
        assertNull(g.cellAt(Offset(60f, 5f)))
        assertNull(g.cellAt(Offset(-2f, 5f)))
        assertNull(g.cellAt(Offset(5f, -3f)))
        assertNull(g.cellAt(Offset(5f, 30f)))
        assertNull(g.cellAt(Offset(70f, 20f)))
    }

    @Test
    fun seatAtUsesTheCellOrTheNearestSeatWithinTolerance() {
        val g = geometry()
        assertEquals(SeatId(1), g.seatAt(Offset(5f, 5f)))
        assertEquals(SeatId(5), g.seatAt(Offset(7f, 15f))) // staggered seat
        assertEquals(SeatId(7), g.seatAt(Offset(55f, 22f)))

        // On the gap cell (row 1, column 1): only with enough tolerance.
        assertNull(g.seatAt(Offset(23f, 19f)))
        assertNull(g.seatAt(Offset(23f, 19f), tolerance = 8f))
        assertEquals(SeatId(5), g.seatAt(Offset(23f, 19f), tolerance = 13f))

        // On the aisle: the nearer neighbour wins.
        assertNull(g.seatAt(Offset(28f, 5f)))
        assertEquals(SeatId(2), g.seatAt(Offset(28f, 5f), tolerance = 12f))
        assertEquals(SeatId(3), g.seatAt(Offset(30f, 5f), tolerance = 12f))

        // Just outside the grid.
        assertEquals(SeatId(1), g.seatAt(Offset(-3f, 5f), tolerance = 10f))
        assertNull(g.seatAt(Offset(-3f, 5f), tolerance = 7f))
    }

    @Test
    fun rowLabelAreasAreLeftAndRightOfTheGrid() {
        val g = geometry()
        assertEquals(0, g.rowLabelAt(Offset(-5f, 5f)))
        assertEquals(1, g.rowLabelAt(Offset(-5f, 19f)))
        assertEquals(0, g.rowLabelAt(Offset(70f, 5f)))
        assertNull(g.rowLabelAt(Offset(30f, 5f)))
        assertNull(g.rowLabelAt(Offset(-5f, 30f)))
        assertNull(geometry(m = metrics.copy(rightLabels = false)).rowLabelAt(Offset(70f, 5f)))
    }

    @Test
    fun cellsAlongASegmentIncludeEveryCrossedCellOnce() {
        val g = geometry()
        assertEquals(
            listOf(CellPosition(0, 0), CellPosition(0, 1), CellPosition(0, 2), CellPosition(0, 3)),
            g.cellsAlong(Offset(5f, 5f), Offset(41f, 5f)),
        )
        assertEquals(listOf(CellPosition(0, 0), CellPosition(1, 0)), g.cellsAlong(Offset(5f, 5f), Offset(5f, 19f)))
        assertEquals(listOf(CellPosition(0, 0)), g.cellsAlong(Offset(5f, 5f), Offset(5f, 5f)))
        // Leaving the grid drops the outside part.
        assertEquals(listOf(CellPosition(0, 4)), g.cellsAlong(Offset(53f, 5f), Offset(80f, 5f)))
    }
}
