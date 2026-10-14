package io.github.katznmeik.cinexperience.core.model

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Rules for [Hall.screenOffset] (spec §4.2): the horizontal shift of the screen's
 * centre from the grid's centre, in seat widths (grid columns). Negative = to the
 * left as seen from the seats facing the screen; 0 = centred.
 *
 * Shared by the layout editor (steppers, dragging), the repositories (which
 * reject invalid values), the backup validator and the seed parser, so a value
 * that one of them accepts is accepted by all of them.
 */
object ScreenOffsetRules {
    /** Step of the offset: half a seat. Valid offsets are whole multiples of it. */
    const val STEP: Float = 0.5f

    /**
     * Largest valid magnitude, independent of the grid: half of the layout
     * editor's widest grid (`LayoutDraft.MAX_COLUMNS` = 60), so the screen centre
     * can reach the side edge of any grid but never lies far outside it.
     */
    const val MAX_MAGNITUDE: Float = 30f

    /** Centred. */
    const val CENTRED: Float = 0f

    /** True if [offset] is finite, a multiple of [STEP] and within ±[MAX_MAGNITUDE]. */
    fun isValid(offset: Float): Boolean =
        offset.isFinite() && abs(offset) <= MAX_MAGNITUDE && (offset / STEP).let { it == it.roundToInt().toFloat() }

    /**
     * Largest sensible magnitude for a grid of [columns] columns: the screen centre
     * stays over the grid (at most half the grid width to either side), and never
     * more than [MAX_MAGNITUDE]. For the editor's steppers and drag handles.
     */
    fun maxMagnitude(columns: Int): Float = min(snapDown(columns.coerceAtLeast(1) / 2f), MAX_MAGNITUDE)

    /**
     * [value] rounded to the nearest [STEP] and clamped to ±[maxMagnitude] of
     * [columns] (or ±[MAX_MAGNITUDE] when [columns] is `null`). NaN gives
     * [CENTRED]. The result is always [isValid] and never `-0.0` (an Int times
     * [STEP]), so equal offsets compare equal.
     */
    fun snap(value: Float, columns: Int? = null): Float {
        if (value.isNaN()) return CENTRED
        val limit = columns?.let(::maxMagnitude) ?: MAX_MAGNITUDE
        val clamped = value.coerceIn(-limit, limit)
        return (clamped / STEP).roundToInt() * STEP
    }

    private fun snapDown(value: Float): Float = (value / STEP).toInt() * STEP
}
