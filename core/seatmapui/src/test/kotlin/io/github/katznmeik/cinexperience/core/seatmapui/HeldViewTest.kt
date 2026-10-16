package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A held view ([SeatMapViewState.hold]) keeps its scale while the content's size
 * changes under it, also when the zoom limits, which follow the content, move
 * past the held scale (the layout editor's screen drags, see `EditorCanvas`).
 */
class HeldViewTest {

    private val viewport = Size(400f, 300f)

    private fun boundsOf(content: Rect) = ViewBounds.of(viewport, content, maxFitScale = 2.5f, maxZoomScale = 3f)

    // Content 200 × 100 starting at (-20, -40): fit scale 2, minimum 2, maximum 8.
    private val bounds = boundsOf(Rect(-20f, -40f, 180f, 60f))

    @Test
    fun aHeldViewKeepsItsScaleWhenTheContentShrinksBelowIt() {
        val state = SeatMapViewState()
        val asDrawn = state.transformFor(bounds)
        state.hold(asDrawn, bounds)

        // The screen's handle is dragged inward: the content narrows to 150, so the
        // fitted (and minimum) scale rises to 2.67, above the held scale 2.
        val shrunk = boundsOf(Rect(-20f, -40f, 130f, 60f))
        assertTrue(shrunk.minScale > asDrawn.scale)
        assertTrue(shrunk.clamp(asDrawn).scale > asDrawn.scale) // the plain clamp would zoom in

        val held = state.transformFor(shrunk)
        assertEquals(asDrawn.scale, held.scale, 0f)
        assertEquals(asDrawn, held) // nothing moved: the content still fits the viewport
        // A content point stays under its screen point.
        assertEquals(asDrawn.toScreen(Offset(100f, 0f)), held.toScreen(Offset(100f, 0f)))

        // Released: the view settles into the fitted view of the new content.
        state.release()
        assertFalse(state.isAdjusted)
        assertEquals(shrunk.fitted(), state.transformFor(shrunk))
    }

    @Test
    fun aHeldViewAtMaximumZoomKeepsItsScaleWhenTheContentGrowsPastIt() {
        val state = SeatMapViewState()
        // Zoomed in to the maximum (4 × the fitted scale), at the content's right edge.
        state.userTransform = bounds.clamp(ViewTransform(8f, Offset(-5000f, 0f)))
        val asDrawn = state.transformFor(bounds)
        assertEquals(8f, asDrawn.scale, 0f)
        state.hold(asDrawn, bounds)
        assertTrue(state.isAdjusted) // still the user's zoom

        // The screen is widened past the grid: the content grows to 300, so the
        // maximum scale drops to 5.33, below the held scale.
        val grown = boundsOf(Rect(-120f, -40f, 180f, 60f))
        assertTrue(grown.maxScale < asDrawn.scale)
        val held = state.transformFor(grown)
        assertEquals(asDrawn.scale, held.scale, 0f)
        assertEquals(asDrawn, held) // the view is panned to the right edge, which the content still covers

        // Released: the user's view is drawn again, limited by the new bounds.
        state.release()
        assertTrue(state.isAdjusted)
        assertEquals(grown.clamp(state.userTransform!!), state.transformFor(grown))
        assertEquals(grown.maxScale, state.transformFor(grown).scale, 0f)
    }

    @Test
    fun theHeldViewIsTheViewAsDrawnAlsoWhenTheUserHasZoomed() {
        val state = SeatMapViewState()
        state.userTransform = ViewTransform(4f, Offset(-100f, -100f))
        val asDrawn = state.transformFor(bounds)
        state.hold(asDrawn, bounds)
        // While held, the content changes (the user's zoom isn't re-clamped meanwhile) ...
        val grown = boundsOf(Rect(-120f, -40f, 180f, 60f))
        assertEquals(asDrawn, state.transformFor(grown))
        // ... and when it is released, the user's view stays where it was held.
        state.release()
        assertTrue(state.isAdjusted)
        assertEquals(asDrawn, state.userTransform)
        assertEquals(asDrawn, state.transformFor(grown))
    }

    @Test
    fun aHeldViewPansSidewaysOverTheContentAsItWasWhenHeld() {
        val state = SeatMapViewState()
        // Zoomed in, at the content's right and bottom edges.
        val asDrawn = bounds.clamp(ViewTransform(4f, Offset(-5000f, -5000f)))
        state.userTransform = asDrawn
        state.hold(asDrawn, bounds)
        // The content's right edge moves left (the screen dragged left past the row
        // labels moves its left edge out and its right edge in): the view stays still,
        // and the right edge of the content as it was when held is still at the viewport's edge.
        val narrower = boundsOf(Rect(-20f, -40f, 100f, 60f))
        assertTrue(narrower.clamp(asDrawn) != asDrawn) // the plain clamp would pan the view
        assertEquals(asDrawn, state.transformFor(narrower))
        assertEquals(viewport.width, state.transformFor(narrower).toScreen(Offset(180f, 0f)).x, 0.001f)

        // Vertically the view keeps to the content as it is now: its bottom edge moves up, the view follows.
        val shorter = boundsOf(Rect(-20f, -40f, 100f, 50f))
        val held = state.transformFor(shorter)
        assertEquals(asDrawn.scale, held.scale, 0f)
        assertEquals(asDrawn.offset.x, held.offset.x, 0f)
        assertEquals(viewport.height, held.toScreen(Offset(0f, 50f)).y, 0.001f)
    }
}
