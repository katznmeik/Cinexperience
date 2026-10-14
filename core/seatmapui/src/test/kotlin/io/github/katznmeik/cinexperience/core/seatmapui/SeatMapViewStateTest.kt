package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatMapViewStateTest {

    // Content 200 × 100 starting at (-20, -40), viewport 400 × 300 (fit scale 2).
    private val bounds = ViewBounds.of(Size(400f, 300f), Rect(-20f, -40f, 180f, 60f), maxFitScale = 2.5f, maxZoomScale = 3f)

    @Test
    fun theViewFollowsTheFittedViewUntilTheUserZooms() {
        val state = SeatMapViewState()
        assertFalse(state.isAdjusted)
        assertEquals(bounds.fitted(), state.transformFor(bounds))
        // The content changes (e.g. a wider screen): still fitted.
        val wider = ViewBounds.of(Size(400f, 300f), Rect(-60f, -40f, 180f, 60f), maxFitScale = 2.5f, maxZoomScale = 3f)
        assertEquals(wider.fitted(), state.transformFor(wider))

        state.userTransform = ViewTransform(4f, Offset(-100f, -100f))
        assertTrue(state.isAdjusted)
        assertEquals(bounds.clamp(ViewTransform(4f, Offset(-100f, -100f))), state.transformFor(bounds))
        state.reset()
        assertFalse(state.isAdjusted)
        assertEquals(bounds.fitted(), state.transformFor(bounds))
    }

    /** A drag of the editor's screen handles holds the view still (the content's size changes meanwhile). */
    @Test
    fun aHeldViewStaysStillWithoutCountingAsAZoomByTheUser() {
        val state = SeatMapViewState()
        val asDrawn = bounds.fitted()
        state.hold(asDrawn)
        assertFalse(state.isAdjusted) // no "Fit" button for it

        // The screen grows past the grid's left edge: the content gets wider, the view stays put.
        val grown = ViewBounds.of(Size(400f, 300f), Rect(-60f, -40f, 180f, 60f), maxFitScale = 2.5f, maxZoomScale = 3f)
        val held = state.transformFor(grown)
        assertEquals(grown.clamp(asDrawn), held)
        assertEquals(asDrawn.scale, held.scale)
        assertTrue("not refitted", held != grown.fitted())

        // Released: the view follows the fitted view again.
        state.release()
        assertFalse(state.isAdjusted)
        assertEquals(grown.fitted(), state.transformFor(grown))
    }

    @Test
    fun holdingKeepsAViewTheUserZoomed() {
        val state = SeatMapViewState()
        val zoomed = ViewTransform(4f, Offset(-100f, -100f))
        state.userTransform = zoomed
        state.hold(bounds.fitted()) // ignored: the user's view is held anyway
        assertEquals(bounds.clamp(zoomed), state.transformFor(bounds))
        state.release()
        assertTrue(state.isAdjusted)
        assertEquals(bounds.clamp(zoomed), state.transformFor(bounds))
    }
}
