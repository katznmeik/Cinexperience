package io.github.katznmeik.cinexperience.core.model

import kotlin.math.max

/**
 * Bounds of a hall's screen size (spec §4.2): [Hall.screenWidth] in seat widths
 * and [Hall.screenDistance] in row depths between the screen and row 1.
 *
 * Shared by the layout editor (its steppers and drag handles stay within
 * [MIN_WIDTH]..[maxWidth] and 0.5..[MAX_DISTANCE]: it sets no distance below half
 * a row), the repositories (which refuse other values) and the backup validator,
 * so whatever the editor can set is saved and exported, and a backup can't bring
 * in a screen so large that the map scales to nothing.
 */
object ScreenSizeRules {
    /** The editor's narrowest screen. Stored widths only need to be positive ([isValidWidth]). */
    const val MIN_WIDTH: Float = 1f

    /**
     * A stored screen may stand right in front of row 1. The layout editor itself
     * sets at least half a row, so the line of its screen bar stays in front of
     * the first row.
     */
    const val MIN_DISTANCE: Float = 0f

    /** The farthest screen, in row depths (the seed's farthest is 2). */
    const val MAX_DISTANCE: Float = 15f

    /** The widest screen: [maxWidth] of the widest grid, [SeatLayout.MAX_COLUMNS] (the seed's widest is 34). */
    const val MAX_WIDTH: Float = 2f * SeatLayout.MAX_COLUMNS

    /** The widest screen the editor allows over a grid of [columns]: twice the grid, at least 2. */
    fun maxWidth(columns: Int): Float = max(columns * 2f, 2f)

    /** A stored screen width: `null` (not set), or finite, positive and at most [MAX_WIDTH]. */
    fun isValidWidth(width: Float?): Boolean = width == null || (width.isFinite() && width > 0f && width <= MAX_WIDTH)

    /** A stored screen distance: `null` (not set), or within [MIN_DISTANCE]..[MAX_DISTANCE]. */
    fun isValidDistance(distance: Float?): Boolean =
        distance == null || (distance.isFinite() && distance >= MIN_DISTANCE && distance <= MAX_DISTANCE)
}
