package io.github.katznmeik.cinexperience.core.model

import kotlin.math.atan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewingAngleTest {

    private val eps = 1e-9

    @Test
    fun centredSeatSeesSymmetricScreen() {
        // 4 columns → centre at x = 2. Seat at column 1 with half offset → x = 2.0.
        val layout = layoutOf("SSSS", "SSS.").updateRow(1) { it.copy(halfSeatOffset = true) }
        val angle = viewingAngle(layout, screenWidth = 4f, screenDistance = 1.5f, seatId = SeatId(6), rowDepthInSeatWidths = 1.0)!!
        // Depth = 1.5 + 1 + 0.5 = 3; half width 2 → FOV = 2·atan(2/3).
        assertEquals(Math.toDegrees(2 * atan(2.0 / 3.0)), angle.fieldOfViewDegrees, eps)
        assertEquals(0.0, angle.offAxisDegrees, eps)
    }

    @Test
    fun sideSeatIsOffAxisAndSeesSmallerAngle() {
        val layout = layoutOf("SSSSSS", "SSSSSS")
        val centreish = viewingAngle(layout, 6f, 2f, SeatId(9), 1.0)!! // row 1, col 2 → x 2.5
        val edge = viewingAngle(layout, 6f, 2f, SeatId(7), 1.0)!! // row 1, col 0 → x 0.5
        assertTrue(edge.offAxisDegrees > centreish.offAxisDegrees)
        assertTrue(edge.fieldOfViewDegrees < centreish.fieldOfViewDegrees)
        // Depth 3.5, screen centre 3 → off-axis of the edge seat = atan(2.5 / 3.5).
        assertEquals(Math.toDegrees(atan(2.5 / 3.5)), edge.offAxisDegrees, eps)
        // Left and right halves mirror each other.
        val otherEdge = viewingAngle(layout, 6f, 2f, SeatId(12), 1.0)!!
        assertEquals(edge.offAxisDegrees, otherEdge.offAxisDegrees, eps)
        assertEquals(edge.fieldOfViewDegrees, otherEdge.fieldOfViewDegrees, eps)
    }

    @Test
    fun rowsFurtherBackSeeASmallerAngle() {
        val layout = layoutOf("S", "S", "S")
        val front = viewingAngle(layout, 1f, 1f, SeatId(1))!!
        val back = viewingAngle(layout, 1f, 1f, SeatId(3))!!
        assertTrue(back.fieldOfViewDegrees < front.fieldOfViewDegrees)
    }

    @Test
    fun defaultRowDepthRatioIsApplied() {
        val layout = layoutOf("SS")
        val angle = viewingAngle(layout, 2f, 0.5f, SeatId(1))!!
        // Seat x = 0.5, screen [0, 2], depth = (0.5 + 0.5) · 1.7.
        val depth = DEFAULT_ROW_DEPTH_IN_SEAT_WIDTHS
        val expected = Math.toDegrees(atan(1.5 / depth) + atan(0.5 / depth))
        assertEquals(expected, angle.fieldOfViewDegrees, eps)
    }

    @Test
    fun seatInFrontOfScreenEdgeStillHasPositiveAngle() {
        val layout = layoutOf("SSSSSSSSSS")
        val angle = viewingAngle(layout, 2f, 0f, SeatId(1))!!
        assertTrue(angle.fieldOfViewDegrees > 0)
    }

    @Test
    fun missingGeometryOrSeatGivesNull() {
        val layout = layoutOf("S")
        assertNull(viewingAngle(layout, null, 1f, SeatId(1)))
        assertNull(viewingAngle(layout, 1f, null, SeatId(1)))
        assertNull(viewingAngle(layout, 0f, 1f, SeatId(1)))
        assertNull(viewingAngle(layout, 1f, -1f, SeatId(1)))
        assertNull(viewingAngle(layout, Float.NaN, 1f, SeatId(1)))
        assertNull(viewingAngle(layout, 1f, 1f, SeatId(2)))
        assertNull(viewingAngle(layout, 1f, 1f, SeatId(1), rowDepthInSeatWidths = 0.0))
    }
}
