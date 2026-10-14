package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewTransformTest {

    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals("x", expected.x, actual.x, 0.001f)
        assertEquals("y", expected.y, actual.y, 0.001f)
    }

    @Test
    fun screenAndContentCoordinatesRoundTrip() {
        val t = ViewTransform(scale = 2f, offset = Offset(10f, -4f))
        assertOffset(Offset(30f, 16f), t.toScreen(Offset(10f, 10f)))
        assertOffset(Offset(10f, 10f), t.toContent(Offset(30f, 16f)))
        assertEquals(Rect(10f, -4f, 30f, 16f), t.toScreen(Rect(0f, 0f, 10f, 10f)))
    }

    @Test
    fun zoomKeepsTheFocusPointInPlace() {
        val t = ViewTransform(1.5f, Offset(7f, 3f))
        val focus = Offset(100f, 50f)
        val before = t.toContent(focus)
        val zoomed = t.zoomed(2f, focus)
        assertEquals(3f, zoomed.scale)
        assertOffset(before, zoomed.toContent(focus))
        assertOffset(Offset(12f, -5f), t.panned(Offset(5f, -8f)).offset)
    }

    // Content 200 × 100 starting at (-20, -40), viewport 400 × 300.
    private val content = Rect(-20f, -40f, 180f, 60f)
    private val bounds = ViewBounds.of(Size(400f, 300f), content, maxFitScale = 2.5f, maxZoomScale = 3f)

    @Test
    fun fitsToWidthCentredAndTopAligned() {
        assertEquals(2f, bounds.fitScale)
        val fitted = bounds.fitted()
        assertEquals(2f, fitted.scale)
        assertOffset(Offset(0f, 0f), fitted.toScreen(content.topLeft)) // left edge at 0, top at 0
        assertOffset(Offset(400f, 200f), fitted.toScreen(content.bottomRight))
        // Content 200 high at scale 2 > 300? No: 100 × 2 = 200, all visible; zooming out stops at fit width.
        assertEquals(2f, bounds.minScale)
        assertEquals(8f, bounds.maxScale) // 4 × fit beats the seat-size limit 3
        assertFalse(bounds.canPan(fitted))
    }

    @Test
    fun smallHallsAreNotMagnifiedBeyondTheFitLimitAndAreCentred() {
        val small = ViewBounds.of(Size(400f, 300f), Rect(0f, 0f, 40f, 20f), maxFitScale = 2.5f, maxZoomScale = 3f)
        assertEquals(2.5f, small.fitScale)
        val fitted = small.fitted()
        assertOffset(Offset(150f, 0f), fitted.offset) // (400 − 40 × 2.5) / 2
        assertEquals(10f, small.maxScale)
    }

    @Test
    fun tallContentCanZoomOutToShowEverything() {
        val tall = ViewBounds.of(Size(400f, 300f), Rect(0f, 0f, 100f, 300f), maxFitScale = 2.5f, maxZoomScale = 3f)
        assertEquals(2.5f, tall.fitScale)
        assertEquals(1f, tall.minScale) // 300 / 300
        assertTrue(tall.canPan(tall.fitted()))
    }

    @Test
    fun tallContentFittedWholeIsScaledToTheHeightAndCentred() {
        // 100 × 300 content in 400 × 300: the width alone would fit it at 2.5 (750 px high).
        val whole = ViewBounds.of(Size(400f, 300f), Rect(0f, 0f, 100f, 300f), maxFitScale = 2.5f, maxZoomScale = 3f, fitHeight = true)
        assertEquals(1f, whole.fitScale)
        assertEquals(1f, whole.minScale)
        assertEquals(10f, whole.maxScale) // zooming in still goes as far as with the width fit (4 × 2.5)
        val fitted = whole.fitted()
        assertOffset(Offset(150f, 0f), fitted.toScreen(Offset.Zero)) // (400 − 100) / 2, top at 0
        assertOffset(Offset(250f, 300f), fitted.toScreen(Offset(100f, 300f))) // the last row in view
        // Nothing to pan at the fitted view: one-finger drags scroll the page.
        assertFalse(whole.canPan(fitted))
        // Double tap zooms in and back to the whole hall.
        val zoomedIn = whole.toggleZoom(fitted, Offset(200f, 150f))!!
        assertEquals(2.5f, zoomedIn.scale)
        assertTrue(whole.canPan(zoomedIn))
        assertNull(whole.toggleZoom(zoomedIn, Offset(200f, 150f)))
    }

    @Test
    fun fittingWholeChangesNothingForContentThatFitsTheWidth() {
        val whole = ViewBounds.of(Size(400f, 300f), content, maxFitScale = 2.5f, maxZoomScale = 3f, fitHeight = true)
        assertEquals(bounds, whole)
        val small = Rect(0f, 0f, 40f, 20f)
        assertEquals(
            ViewBounds.of(Size(400f, 300f), small, maxFitScale = 2.5f, maxZoomScale = 3f),
            ViewBounds.of(Size(400f, 300f), small, maxFitScale = 2.5f, maxZoomScale = 3f, fitHeight = true),
        )
    }

    @Test
    fun aPageMapIsAsTallAsTheWindowBetweenTheBarsButNeverShorterThanBefore() {
        assertEquals(715.dp, seatMapMaxHeightFor(915.dp)) // phone: 915 − 200
        assertEquals(DEFAULT_SEAT_MAP_MAX_HEIGHT, seatMapMaxHeightFor(720.dp)) // 520 floor
        assertEquals(DEFAULT_SEAT_MAP_MAX_HEIGHT, seatMapMaxHeightFor(412.dp)) // landscape: the page scrolls
        assertEquals(1080.dp, seatMapMaxHeightFor(1280.dp))
    }

    @Test
    fun clampLimitsScaleAndKeepsContentEdgesInView() {
        // Panned far away: pulled back so no empty space shows beyond the content.
        val zoomed = bounds.clamp(ViewTransform(4f, Offset(500f, 500f)))
        assertEquals(4f, zoomed.scale)
        assertOffset(Offset(80f, 160f), zoomed.offset) // content's top-left at the viewport's top-left
        val other = bounds.clamp(ViewTransform(4f, Offset(-5000f, -5000f)))
        assertOffset(Offset(400f - 180f * 4f, 300f - 60f * 4f), other.offset) // bottom-right corner
        assertTrue(bounds.canPan(zoomed))

        // Scale limits.
        assertEquals(8f, bounds.clamp(ViewTransform(50f, Offset.Zero)).scale)
        assertEquals(2f, bounds.clamp(ViewTransform(0.1f, Offset.Zero)).scale)

        // Content smaller than the viewport stays fully inside it.
        val inside = bounds.clamp(ViewTransform(2f, Offset(100f, 100f)))
        assertOffset(Offset(40f, 100f), inside.offset)
    }

    @Test
    fun pinchingPastTheZoomLimitsKeepsTheContentUnderTheFingers() {
        val focus = Offset(130f, 170f)
        var t = bounds.clamp(ViewTransform(7f, Offset(-400f, -250f)))
        val under = t.toContent(focus)
        repeat(3) { t = bounds.pinched(t, focus, Offset.Zero, zoom = 1.5f) } // 7 → limited at 8
        assertEquals(8f, t.scale)
        assertOffset(under, t.toContent(focus))
        t = bounds.pinched(t, focus, Offset.Zero, zoom = 1.5f) // already at the limit: nothing moves
        assertOffset(under, t.toContent(focus))

        // Zooming out stops at the minimum the same way.
        val out = bounds.pinched(bounds.fitted(), Offset(200f, 100f), Offset.Zero, zoom = 0.5f)
        assertEquals(2f, out.scale)
        assertOffset(bounds.fitted().offset, out.offset)

        // Pan still applies.
        val panned = bounds.pinched(ViewTransform(4f, Offset(0f, 100f)), focus, Offset(-10f, -5f), zoom = 1f)
        assertOffset(Offset(-10f, 95f), panned.offset)
    }

    @Test
    fun theTopInsetStaysFreeAtEveryZoom() {
        // The same content with 30 px kept free above it (the editor's screen label).
        val inset = ViewBounds.of(Size(400f, 300f), content, maxFitScale = 2.5f, maxZoomScale = 3f, topInset = 30f)
        assertEquals(2f, inset.fitScale) // the width alone decides the fit
        assertOffset(Offset(0f, 30f), inset.fitted().toScreen(content.topLeft))
        // Zoomed in and pulled down: the content's top stops at the inset ...
        val zoomed = inset.clamp(ViewTransform(4f, Offset(500f, 500f)))
        assertOffset(Offset(0f, 30f), zoomed.toScreen(content.topLeft))
        assertTrue(inset.canPan(zoomed))
        // ... and pushed up, its bottom still reaches the viewport's bottom.
        val up = inset.clamp(ViewTransform(4f, Offset(-5000f, -5000f)))
        assertEquals(300f, up.toScreen(content.bottomRight).y, 0.001f)

        // Zoomed out as far as it goes, all content fits below the inset: 300 high in 270 px.
        val tall = ViewBounds.of(Size(400f, 300f), Rect(0f, 0f, 100f, 300f), maxFitScale = 2.5f, maxZoomScale = 3f, topInset = 30f)
        assertEquals(0.9f, tall.minScale, 0.001f)
        val all = tall.clamp(ViewTransform(0.9f, Offset.Zero))
        assertEquals(30f, all.toScreen(Offset.Zero).y, 0.001f)
        assertFalse(tall.canPan(all))
    }

    @Test
    fun doubleTapZoomsInThenBackToTheFittedView() {
        val zoomedIn = bounds.toggleZoom(bounds.fitted(), Offset(200f, 100f))!!
        assertEquals(5f, zoomedIn.scale) // 2.5 × fit
        assertNull(bounds.toggleZoom(zoomedIn, Offset(200f, 100f)))
    }

    @Test
    fun hitTestingThroughTheTransformFindsTheTappedSeat() {
        val layout = SeatLayout.fromPatterns("SSSS", "SS.S", firstSeatId = 1)
            .updateRow(1) { it.copy(halfSeatOffset = true) }
        val metrics = SeatMapMetrics(10f, 2f, 4f, 8f, 2f, 5f, 0f, 1f)
        val geometry = SeatMapGeometry(layout, metrics, screenWidth = 4f, screenDistance = 1f)
        val view = ViewBounds.of(Size(240f, 200f), geometry.contentBounds, 2.5f, 3f)
        val zoomed = view.clamp(view.fitted().zoomed(2f, Offset(120f, 60f)).panned(Offset(-30f, 10f)))
        for (transform in listOf(view.fitted(), zoomed)) {
            for (id in layout.seatIds) {
                val onScreen = transform.toScreen(geometry.seatCenter(id)!!)
                assertEquals(id, geometry.seatAt(transform.toContent(onScreen)))
            }
            // A finger slightly off the seat (in screen px) still hits it with a screen-sized tolerance.
            val nearSeven = transform.toScreen(geometry.seatCenter(SeatId(7))!!) + Offset(0f, 8f * transform.scale)
            assertEquals(SeatId(7), geometry.seatAt(transform.toContent(nearSeven), tolerance = 10f))
        }
    }
}
