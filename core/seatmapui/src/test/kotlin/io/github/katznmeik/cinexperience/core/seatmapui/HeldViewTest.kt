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
        state.hold(asDrawn)

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
        state.hold(asDrawn)
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
        state.hold(asDrawn)
        // While held, the content changes (the user's zoom isn't re-clamped meanwhile) ...
        val grown = boundsOf(Rect(-120f, -40f, 180f, 60f))
        assertEquals(asDrawn, state.transformFor(grown))
        // ... and the user's zoom survives the hold untouched.
        state.release()
        assertEquals(ViewTransform(4f, Offset(-100f, -100f)), state.userTransform)
        assertEquals(grown.clamp(ViewTransform(4f, Offset(-100f, -100f))), state.transformFor(grown))
    }

    @Test
    fun aHeldViewStillKeepsTheContentInView() {
        val state = SeatMapViewState()
        val asDrawn = bounds.clamp(ViewTransform(4f, Offset(-5000f, 0f))) // zoomed, at the right edge
        state.userTransform = asDrawn
        state.hold(asDrawn)
        // The content's right edge moves left: the view follows it, so no empty space shows.
        val narrower = boundsOf(Rect(-20f, -40f, 100f, 60f))
        val held = state.transformFor(narrower)
        assertEquals(asDrawn.scale, held.scale, 0f)
        assertEquals(viewport.width, held.toScreen(Offset(100f, 0f)).x, 0.001f)
    }
}
