package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.seatmapui.MAX_ZOOM_SEAT_SIZE
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMapGeometry
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMapViewState
import io.github.katznmeik.cinexperience.core.seatmapui.ViewBounds
import io.github.katznmeik.cinexperience.core.seatmapui.ViewTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A screen drag on the editor canvas (`EditorCanvas`): the drag turns the finger's
 * movement into a width or offset with the scale captured when it started
 * ([draggedScreenWidth], [draggedScreenOffset]), and the view is held meanwhile,
 * so the handle or bar moves exactly with the finger although the content's
 * width (and with it the fitted zoom) changes under it.
 */
class ScreenDragTest {

    /** The editor at density 1 on a 328 px wide canvas. */
    private val density = Density(1f)
    private val metrics = editorMetrics(density, labelWidth = 26f)
    private val canvasWidth = 328f
    private val maxZoomScale = with(density) { MAX_ZOOM_SEAT_SIZE.toPx() } / metrics.seatSize

    private fun bounds(geometry: SeatMapGeometry, viewport: Size): ViewBounds = editorViewBounds(
        viewport,
        geometry,
        maxZoomScale,
        labelHeight = with(density) { SCREEN_LABEL_HEIGHT.toPx() },
        handleRoom = with(density) { HANDLE_ROOM.toPx() },
    )

    /** The canvas as measured for [geometry] at the fitted zoom. */
    private fun fittedViewport(geometry: SeatMapGeometry): Size {
        val fitted = bounds(geometry, Size(canvasWidth, 0f))
        return Size(canvasWidth, with(density) { editorCanvasHeight(fitted, fitted.fitScale) }.toFloat())
    }

    private fun geometry(layout: SeatLayout, width: Float, offset: Float = 0f) =
        SeatMapGeometry(layout, metrics, screenWidth = width, screenDistance = 1.5f, screenOffset = offset)

    private fun assertOffset(message: String, expected: Offset, actual: Offset) {
        assertEquals("$message: x", expected.x, actual.x, 0.01f)
        assertEquals("$message: y", expected.y, actual.y, 0.01f)
    }

    @Test
    fun aHandleDragMovesTheHandleWithTheFinger() {
        // A small hall with a screen twice as wide as its grid: the screen bounds the content's width.
        val layout = SeatLayout.fromPatterns(List(5) { "S".repeat(7) }, firstSeatId = 1)
        val start = geometry(layout, 14f)
        val viewport = fittedViewport(start)
        val atDown = bounds(start, viewport)
        val state = SeatMapViewState()
        val down = state.transformFor(atDown) // fitted
        state.hold(down, atDown)
        val handleAtDown = down.toScreen(Offset(start.screenRight, start.screenY))

        // The finger moves 50 px inward on the right handle.
        val finger = Offset(-50f, 0f)
        val dragged = geometry(layout, draggedScreenWidth(14f, right = true, finger.x, metrics.pitchX, down.scale))
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

    /**
     * Drags the bar of a 17-column hall (Babylon Saal 1) with a screen as wide as
     * its grid from offset [from] by [seats] seat widths and checks that the bar
     * follows the finger and the grid stays put.
     */
    private fun assertBarFollowsTheFinger(from: Float, seats: Float, zoomChanges: Boolean) {
        val layout = SeatLayout.fromPatterns(List(10) { "S".repeat(17) }, firstSeatId = 1)
        val start = geometry(layout, 17f, from)
        val viewport = fittedViewport(start)
        val atDown = bounds(start, viewport)
        val state = SeatMapViewState()
        val down = state.transformFor(atDown)
        state.hold(down, atDown)
        val barAtDown = down.toScreen(Offset(start.screenCenterX, start.screenY))

        val finger = Offset(seats * metrics.pitchX * down.scale, 0f)
        val offset = draggedScreenOffset(from, finger.x, metrics.pitchX, down.scale)
        assertEquals(from + seats, offset, 0.001f)
        val dragged = geometry(layout, 17f, offset)
        val live = bounds(dragged, viewport)
        if (zoomChanges) assertTrue("the fitted zoom rises with the narrower content", live.fitScale > down.scale * 1.02f)
        // The content's edges move: the plain clamp would pan or zoom the view.
        assertTrue(live.clamp(down) != down)

        val drawn = state.transformFor(live)
        assertEquals("the held view keeps its zoom", down.scale, drawn.scale, 0f)
        assertOffset("the bar follows the finger", barAtDown + finger, drawn.toScreen(Offset(dragged.screenCenterX, dragged.screenY)))
        assertOffset("the grid stays put", down.toScreen(start.cellCenter(4, 8)), drawn.toScreen(dragged.cellCenter(4, 8)))
    }

    /** The screen was moved 2 seats right, so it overhangs the grid: dragging it back narrows the content. */
    @Test
    fun aBarDraggedBackToTheCentreFollowsTheFinger() = assertBarFollowsTheFinger(from = 2f, seats = -2f, zoomChanges = true)

    /** Past the row labels on the left: the content's left edge moves out, its right edge in. */
    @Test
    fun aBarDraggedLeftPastTheRowLabelsFollowsTheFinger() = assertBarFollowsTheFinger(from = 0f, seats = -2f, zoomChanges = false)

    @Test
    fun aBarDraggedBackFromTheLeftFollowsTheFinger() = assertBarFollowsTheFinger(from = -2f, seats = 2f, zoomChanges = false)

    @Test
    fun aHandleDragAtMaximumZoomKeepsTheZoom() {
        // Zoomed in to the maximum (4 × the fitted zoom) at the right edge, the screen as wide as the grid.
        val layout = SeatLayout.fromPatterns(List(5) { "S".repeat(7) }, firstSeatId = 1)
        val start = geometry(layout, 7f)
        val viewport = Size(canvasWidth, 300f)
        val state = SeatMapViewState()
        val atDown = bounds(start, viewport)
        state.userTransform = atDown.clamp(ViewTransform(atDown.maxScale, Offset(-1e6f, 0f)))
        val down = state.transformFor(atDown)
        assertEquals(atDown.maxScale, down.scale, 0f)
        state.hold(down, atDown)
        val handleAtDown = down.toScreen(Offset(start.screenRight, start.screenY))
        assertTrue("the right handle is in view: ${handleAtDown.x}", handleAtDown.x in 0f..canvasWidth - 30f)

        // The finger drags the right handle 30 px outward: the screen grows past the grid,
        // the content widens and the maximum zoom drops below the held zoom.
        val finger = Offset(30f, 0f)
        val dragged = geometry(layout, draggedScreenWidth(7f, right = true, finger.x, metrics.pitchX, down.scale))
        val live = bounds(dragged, viewport)
        assertTrue(live.maxScale < down.scale)
        val drawn = state.transformFor(live)
        assertEquals("the held view keeps its zoom", down.scale, drawn.scale, 0f)
        assertOffset("the handle follows the finger", handleAtDown + finger, drawn.toScreen(Offset(dragged.screenRight, dragged.screenY)))
        assertOffset("the grid stays put", down.toScreen(start.cellCenter(2, 3)), drawn.toScreen(dragged.cellCenter(2, 3)))

        // Released: the user's view stays where it was held, zoomed out to the new maximum.
        state.release()
        assertEquals(live.clamp(drawn), state.transformFor(live))
        assertEquals(live.maxScale, state.transformFor(live).scale, 0f)
    }
}
