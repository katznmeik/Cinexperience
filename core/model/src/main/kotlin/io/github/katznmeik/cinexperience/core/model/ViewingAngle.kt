package io.github.katznmeik.cinexperience.core.model

import kotlin.math.abs
import kotlin.math.atan2

/**
 * Approximate horizontal viewing geometry of one seat (spec §6.3).
 *
 * @property fieldOfViewDegrees horizontal angle the screen covers as seen from the
 *   seat (bigger = more immersive; around 30–40° is often recommended).
 * @property offAxisDegrees angle between "straight ahead" (perpendicular to the
 *   screen) and the line to the screen centre; 0 for a centred seat.
 */
data class ViewingAngle(val fieldOfViewDegrees: Double, val offAxisDegrees: Double)

/**
 * Approximate viewing angle of [seatId], or `null` when the hall has no usable
 * screen geometry or the seat is not in the layout. Pass the hall's
 * [Hall.screenOffset] as [screenOffset] (by name: it is the last parameter).
 *
 * Geometry assumptions (a flat, top-down model; seat heights and rake are ignored):
 * - The unit is one seat width (one grid column). Column `c` spans `[c, c + 1]`;
 *   a row with [LayoutRow.halfSeatOffset] is shifted right by 0.5.
 * - The screen is flat (straight, §4.3) and parallel to the rows. Its centre is
 *   at `columnCount / 2 + screenOffset`, so it spans
 *   `columnCount / 2 + screenOffset ± screenWidth / 2`. [screenWidth] and
 *   [screenOffset] are in seat widths (§4.2); a negative offset moves the screen
 *   to the left (towards column 0), 0 centres it over the grid.
 * - The seat's eye point is the centre of its cell. Its distance to the screen
 *   plane is `(screenDistance + row + 0.5) × rowDepthInSeatWidths`, where
 *   [screenDistance] and the rows are counted in row depths (§4.2). Rows without
 *   seats (e.g. cross aisles) still take up one row depth.
 * - A row is deeper than a seat is wide (≈ 0.9–1.0 m row pitch vs. ≈ 0.55 m
 *   seat width), so row depths are converted with [rowDepthInSeatWidths]
 *   (default [DEFAULT_ROW_DEPTH_IN_SEAT_WIDTHS]). Pass 1.0 to get angles that
 *   match a drawing with square cells.
 */
fun viewingAngle(
    layout: SeatLayout,
    screenWidth: Float?,
    screenDistance: Float?,
    seatId: SeatId,
    rowDepthInSeatWidths: Double = DEFAULT_ROW_DEPTH_IN_SEAT_WIDTHS,
    screenOffset: Float = ScreenOffsetRules.CENTRED,
): ViewingAngle? {
    if (screenWidth == null || screenDistance == null) return null
    if (!(screenWidth > 0f) || !(screenDistance >= 0f) || !(rowDepthInSeatWidths > 0.0)) return null
    if (!screenOffset.isFinite()) return null
    val position = layout.positionOf(seatId) ?: return null
    val offset = if (layout.rows[position.row].halfSeatOffset) 0.5 else 0.0
    val seatX = position.column + 0.5 + offset
    val depth = (screenDistance + position.row + 0.5) * rowDepthInSeatWidths
    val screenCentre = layout.columnCount / 2.0 + screenOffset
    val left = screenCentre - screenWidth / 2.0
    val right = screenCentre + screenWidth / 2.0
    val fieldOfView = Math.toDegrees(atan2(right - seatX, depth) - atan2(left - seatX, depth))
    val offAxis = abs(Math.toDegrees(atan2(screenCentre - seatX, depth)))
    return ViewingAngle(fieldOfView, offAxis)
}

/** Typical row depth / seat width ratio of a cinema (≈ 0.95 m / 0.55 m). */
const val DEFAULT_ROW_DEPTH_IN_SEAT_WIDTHS: Double = 1.7
