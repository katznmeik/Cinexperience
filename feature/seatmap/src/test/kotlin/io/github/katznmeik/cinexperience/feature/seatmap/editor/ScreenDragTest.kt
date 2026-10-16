package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMapGeometry
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMapViewState
import io.github.katznmeik.cinexperience.core.seatmapui.ViewBounds
import io.github.katznmeik.cinexperience.core.seatmapui.ViewTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The maths of a screen drag on the editor canvas (`EditorCanvas`): the drag
 * divides the finger's movement by the scale captured when it started, and the
 * view is held meanwhile, so the handle or bar moves exactly with the finger
 * although the content's width (and with it the fitted zoom) changes under it.
 */
class ScreenDragTest {

    /** The editor at density 1 on a 328 px wide canvas (see [editorMetrics], [editorViewBounds]). */
    private val metrics = editorMetrics(Density(1f), labelWidth = 26f)
    private val canvasWidth = 328f
    private val labelHeight = 34f
    private val handleRoom = 17.5f
    private val bottomSpace = 6f

    private fun bounds(geometry: SeatMapGeometry, viewport: Size) =
        editorViewBounds(viewport, geometry, maxZoomScale = 2.75f, labelHeight = labelHeight, handleRoom = handleRoom)

    /** The canvas's height as measured for [geometry]: the grid at the fitted zoom, within 212..480 (see `EditorCanvas`). */
    private fun canvasHeight(geometry: SeatMapGeometry, fitScale: Float): Float =
        (labelHeight + geometry.contentBounds.height * fitScale + bottomSpace).roundToInt().toFloat().coerceIn(212f, 480f)

    private fun assertOffset(message: String, expected: Offset, actual: Offset) {
        assertEquals("$message: x", expected.x, actual.x, 0.01f)
        assertEquals("$message: y", expected.y, actual.y, 0.01f)
    }

    @Test
    fun aHandleDragMovesTheHandleWithTheFinger() {
        // A small hall with a screen twice as wide as its grid: the screen bounds the content's width.
        val layout = SeatLayout.fromPatterns(List(5) { "S".repeat(7) }, firstSeatId = 1)
        val start = SeatMapGeometry(layout, metrics, screenWidth = 14f, screenDistance = 1.5f)
        val viewport = Size(canvasWidth, canvasHeight(start, bounds(start, Size(canvasWidth, 0f)).fitScale))
        val state = SeatMapViewState()
        val down = state.transformFor(bounds(start, viewport)) // fitted
        state.hold(down)
        val handleAtDown = down.toScreen(Offset(start.screenRight, start.screenY))

        // The finger moves 50 px inward on the right handle.
        val finger = Offset(-50f, 0f)
        val width = 14f + 2f * finger.x / (metrics.pitchX * down.scale)
        val dragged = SeatMapGeometry(layout, metrics, screenWidth = width, screenDistance = 1.5f)
        val live = bounds(dragged, viewport)
        assertTrue("the fitted zoom rises with the narrower content", live.fitScale > down.scale * 1.3f)

        val drawn = state.transformFor(live)
        assertEquals("the held view keeps its zoom", down.scale, drawn.scale, 0f)
        assertOffset("the handle follows the finger", handleAtDown + finger, drawn.toScreen(Offset(dragged.screenRight, dragged.screenY)))
        assertOffset("the grid stays put", down.toScreen(start.cellCenter(2, 3)), drawn.toScreen(dragged.cellCenter(2, 3)))

        // Released: the view settles into the fitted view of the narrower screen.
        state.release()
        assertEquals(live.fitted(), state.transformFor(live))
    }

    @Test
    fun aBarDragMovesTheBarWithTheFinger() {
        // A 17-column hall (Babylon Saal 1) whose screen, as wide as the grid, was moved 2 seats
        // to the right, so it overhangs the grid: dragging it back narrows the content.
        val layout = SeatLayout.fromPatterns(List(10) { "S".repeat(17) }, firstSeatId = 1)
        val start = SeatMapGeometry(layout, metrics, screenWidth = 17f, screenDistance = 1.5f, screenOffset = 2f)
        val viewport = Size(canvasWidth, canvasHeight(start, bounds(start, Size(canvasWidth, 0f)).fitScale))
        val state = SeatMapViewState()
        val down = state.transformFor(bounds(start, viewport))
        state.hold(down)
        val barAtDown = down.toScreen(Offset(start.screenCenterX, start.screenY))

        // The finger drags the bar 2 seats to the left, to the centre.
        val finger = Offset(-2f * metrics.pitchX * down.scale, 0f)
        val offset = 2f + finger.x / (metrics.pitchX * down.scale)
        assertEquals(0f, offset, 0.001f)
        val dragged = SeatMapGeometry(layout, metrics, screenWidth = 17f, screenDistance = 1.5f, screenOffset = offset)
        val live = bounds(dragged, viewport)
        assertTrue("the fitted zoom rises with the narrower content", live.fitScale > down.scale * 1.05f)

        val drawn = state.transformFor(live)
        assertEquals("the held view keeps its zoom", down.scale, drawn.scale, 0f)
        assertOffset("the bar follows the finger", barAtDown + finger, drawn.toScreen(Offset(dragged.screenCenterX, dragged.screenY)))
        assertOffset("the grid stays put", down.toScreen(start.cellCenter(4, 8)), drawn.toScreen(dragged.cellCenter(4, 8)))
    }

    @Test
    fun aHandleDragAtMaximumZoomKeepsTheZoom() {
        // Zoomed in to the maximum (4 × the fitted zoom), the screen as wide as the grid.
        val layout = SeatLayout.fromPatterns(List(5) { "S".repeat(7) }, firstSeatId = 1)
        val start = SeatMapGeometry(layout, metrics, screenWidth = 7f, screenDistance = 1.5f)
        val viewport = Size(canvasWidth, 300f)
        val state = SeatMapViewState()
        val b = bounds(start, viewport)
        state.userTransform = b.clamp(ViewTransform(b.maxScale, Offset(0f, 0f)))
        val down = state.transformFor(b)
        assertEquals(b.maxScale, down.scale, 0f)
        state.hold(down)

        // The finger drags the right handle 30 px outward: the screen grows past the grid,
        // the content widens and the maximum zoom drops below the held zoom.
        val width = 7f + 2f * 30f / (metrics.pitchX * down.scale)
        val dragged = SeatMapGeometry(layout, metrics, screenWidth = width, screenDistance = 1.5f)
        val live: ViewBounds = bounds(dragged, viewport)
        assertTrue(live.maxScale < down.scale)
        val drawn = state.transformFor(live)
        assertEquals("the held view keeps its zoom", down.scale, drawn.scale, 0f)
        assertOffset("the grid stays put", down.toScreen(start.cellCenter(2, 3)), drawn.toScreen(dragged.cellCenter(2, 3)))
    }
}
