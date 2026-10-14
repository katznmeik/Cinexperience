package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.katznmeik.cinexperience.core.model.CellPosition
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Sizes of a seat map drawing, in content units (pixels at zoom 1). The seat map
 * and the layout editor use different metrics for the same [SeatMapGeometry].
 *
 * @property screenTopSpace room above the screen line (its stroke, drag handles).
 * @property minScreenGap minimum room between the screen line and row 0, e.g. for
 *   a "SCREEN" caption; 0 draws the screen distance exactly.
 * @property rightLabels also show row labels on the right of every row.
 * @property screenSideSpace room left and right of the screen's ends (drag handles).
 */
data class SeatMapMetrics(
    val seatSize: Float,
    val seatGap: Float,
    val rowGap: Float,
    val labelWidth: Float,
    val labelGap: Float,
    val screenTopSpace: Float,
    val minScreenGap: Float,
    val padding: Float,
    val rightLabels: Boolean = true,
    val screenSideSpace: Float = 0f,
) {
    /** Horizontal distance between neighbouring cells' left edges. */
    val pitchX: Float get() = seatSize + seatGap

    /** Vertical distance between neighbouring rows' top edges. */
    val pitchY: Float get() = seatSize + rowGap
}

/**
 * Where everything of one [SeatLayout] is drawn, in content coordinates, plus
 * hit-testing (content point → cell, seat or row label). Pure and cheap to build;
 * rebuilt whenever the layout or the screen geometry changes.
 *
 * Coordinates: the origin is the top-left corner of cell (0, 0) without offset;
 * x grows to the right, y towards the back of the hall. Row `r` spans
 * `[r × pitchY, r × pitchY + seatSize]`; column `c` starts at `c × pitchX`, plus
 * half a pitch in rows with a half-seat offset. Row labels sit left (and right)
 * of the grid; the screen is a straight horizontal line above row 0 at
 * `screenY < 0`, placed like in the viewing-angle model
 * (`core.model.viewingAngle`): [screenWidth] seat widths wide, [screenDistance]
 * row depths in front of row 0, its centre [screenOffset] seat widths right of
 * the cell area's centre (negative = left, towards column 0, as seen from the
 * seats facing the screen).
 *
 * @param screenWidth in seat widths; `null` → as wide as the grid.
 * @param screenDistance in row depths; `null` → [DEFAULT_SCREEN_DISTANCE].
 * @param screenOffset in seat widths (`Hall.screenOffset`); 0 = centred.
 */
class SeatMapGeometry(
    val layout: SeatLayout,
    val metrics: SeatMapMetrics,
    screenWidth: Float? = null,
    screenDistance: Float? = null,
    screenOffset: Float = 0f,
) {
    val rowCount: Int = layout.rowCount
    val columnCount: Int = layout.columnCount

    val screenWidth: Float = screenWidth?.takeIf { it > 0f } ?: columnCount.toFloat()
    val screenDistance: Float = screenDistance?.takeIf { it >= 0f } ?: DEFAULT_SCREEN_DISTANCE
    val screenOffset: Float = screenOffset.takeIf { it.isFinite() } ?: 0f

    private val halfShift: Float = metrics.pitchX / 2f
    private val hasOffsetRows: Boolean = layout.rows.any { it.halfSeatOffset }

    /** Width of the cell columns without any half-seat offset. */
    val cellsWidth: Float = columnCount * metrics.pitchX - metrics.seatGap

    /** Width of the grid including the half-seat offset of staggered rows. */
    val gridWidth: Float = cellsWidth + if (hasOffsetRows) halfShift else 0f
    val gridHeight: Float = rowCount * metrics.pitchY - metrics.rowGap

    /** Centre of the screen: the centre of the unshifted cell area, moved by [screenOffset]. */
    val screenCenterX: Float = cellsWidth / 2f + this.screenOffset * metrics.pitchX
    val screenLeft: Float = screenCenterX - this.screenWidth * metrics.pitchX / 2f
    val screenRight: Float = screenCenterX + this.screenWidth * metrics.pitchX / 2f

    /** y of the screen line: [screenDistance] row depths (at least [SeatMapMetrics.minScreenGap]) above row 0. */
    val screenY: Float = 0f - max(this.screenDistance * metrics.pitchY, metrics.minScreenGap)

    private val labelsLeft: Float = -metrics.labelGap - metrics.labelWidth
    private val labelsRight: Float =
        if (metrics.rightLabels) gridWidth + metrics.labelGap + metrics.labelWidth else gridWidth

    /** Everything that is drawn, plus padding. */
    val contentBounds: Rect = Rect(
        left = min(labelsLeft, screenLeft - metrics.screenSideSpace) - metrics.padding,
        top = screenY - metrics.screenTopSpace - metrics.padding,
        right = max(labelsRight, screenRight + metrics.screenSideSpace) + metrics.padding,
        bottom = gridHeight + metrics.padding,
    )

    fun rowTop(row: Int): Float = row * metrics.pitchY

    private fun rowShift(row: Int): Float = if (layout.rows[row].halfSeatOffset) halfShift else 0f

    fun cellLeft(row: Int, column: Int): Float = column * metrics.pitchX + rowShift(row)

    fun cellRect(row: Int, column: Int): Rect {
        val left = cellLeft(row, column)
        val top = rowTop(row)
        return Rect(left, top, left + metrics.seatSize, top + metrics.seatSize)
    }

    fun cellCenter(row: Int, column: Int): Offset =
        Offset(cellLeft(row, column) + metrics.seatSize / 2f, rowTop(row) + metrics.seatSize / 2f)

    fun leftLabelRect(row: Int): Rect =
        Rect(labelsLeft, rowTop(row), -metrics.labelGap, rowTop(row) + metrics.seatSize)

    /** The right label's rect, or `null` without right labels. */
    fun rightLabelRect(row: Int): Rect? = if (!metrics.rightLabels) {
        null
    } else {
        val left = gridWidth + metrics.labelGap
        Rect(left, rowTop(row), left + metrics.labelWidth, rowTop(row) + metrics.seatSize)
    }

    fun seatCenter(seatId: SeatId): Offset? = layout.positionOf(seatId)?.let { cellCenter(it.row, it.column) }

    /** The row whose band (cell height plus half the row gap on each side) contains [y]. */
    private fun rowAt(y: Float): Int? {
        val row = floor((y + metrics.rowGap / 2f) / metrics.pitchY).toInt()
        return row.takeIf { it in 0 until rowCount }
    }

    /**
     * The grid cell under [point], or `null` outside the grid. The gaps between
     * cells belong to the nearest cell, so a drag never "falls through" a gap.
     */
    fun cellAt(point: Offset): CellPosition? {
        val row = rowAt(point.y) ?: return null
        val column = floor((point.x - rowShift(row) + metrics.seatGap / 2f) / metrics.pitchX).toInt()
        if (column !in 0 until columnCount) return null
        return CellPosition(row, column)
    }

    /**
     * The seat at [point]: the seat whose cell contains it, else the seat whose
     * centre is nearest within [tolerance] (content units; for small seats under
     * a finger). `null` if there is none.
     */
    fun seatAt(point: Offset, tolerance: Float = 0f): SeatId? {
        cellAt(point)?.let { (row, column) ->
            (layout.cell(row, column) as? LayoutCell.Seat)?.let { return it.id }
        }
        if (tolerance <= 0f) return null
        val firstRow = floor((point.y - tolerance) / metrics.pitchY).toInt().coerceAtLeast(0)
        val lastRow = ceil((point.y + tolerance) / metrics.pitchY).toInt().coerceAtMost(rowCount - 1)
        var best: SeatId? = null
        var bestDistance = tolerance * tolerance
        for (row in firstRow..lastRow) {
            val shift = rowShift(row)
            val firstColumn = floor((point.x - shift - tolerance) / metrics.pitchX).toInt().coerceAtLeast(0)
            val lastColumn = ceil((point.x - shift + tolerance) / metrics.pitchX).toInt().coerceAtMost(columnCount - 1)
            for (column in firstColumn..lastColumn) {
                val seat = layout.cell(row, column) as? LayoutCell.Seat ?: continue
                val distance = (cellCenter(row, column) - point).getDistanceSquared()
                if (distance <= bestDistance) {
                    best = seat.id
                    bestDistance = distance
                }
            }
        }
        return best
    }

    /**
     * The row whose label area contains [point]: anything left of the grid (or
     * right of it, with right labels) within the row's band. `null` otherwise.
     */
    fun rowLabelAt(point: Offset): Int? {
        val row = rowAt(point.y) ?: return null
        val inLeft = point.x < -metrics.labelGap / 2f && point.x >= contentBounds.left
        val inRight = metrics.rightLabels && point.x > gridWidth + metrics.labelGap / 2f && point.x <= contentBounds.right
        return row.takeIf { inLeft || inRight }
    }

    /**
     * The cells crossed by the straight segment [from] → [to], in order and without
     * repeats, so a fast drag paints every cell it passes over.
     */
    fun cellsAlong(from: Offset, to: Offset): List<CellPosition> {
        val step = min(metrics.pitchX, metrics.pitchY) / 4f
        val steps = ceil((to - from).getDistance() / step).toInt().coerceAtLeast(1)
        val cells = LinkedHashSet<CellPosition>()
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            cellAt(Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t))?.let(cells::add)
        }
        return cells.toList()
    }

    companion object {
        /** Screen distance drawn for a hall without one, in row depths. */
        const val DEFAULT_SCREEN_DISTANCE: Float = 2f
    }
}
