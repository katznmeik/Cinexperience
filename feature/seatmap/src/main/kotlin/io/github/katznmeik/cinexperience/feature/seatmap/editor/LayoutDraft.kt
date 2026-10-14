package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.LayoutCell
import io.github.katznmeik.cinexperience.core.model.LayoutEditSession
import io.github.katznmeik.cinexperience.core.model.LayoutLabels
import io.github.katznmeik.cinexperience.core.model.LayoutRow
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.ScreenOffsetRules
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Everything the layout editor saves (spec §6.5): the layout, edited only
 * through its [LayoutEditSession] so seat ids stay correct, the screen geometry
 * (width, distance and the sideways [screenOffset]) and the reference
 * screenshot. Immutable; every edit returns a new draft, which is what the undo
 * history stores.
 *
 * @property screenOffset seat widths, see `Hall.screenOffset`; always
 *   [ScreenOffsetRules.isValid].
 */
data class LayoutDraft(
    val session: LayoutEditSession,
    val screenWidth: Float,
    val screenDistance: Float,
    val referenceImagePath: String?,
    val screenOffset: Float = ScreenOffsetRules.CENTRED,
) {
    /** The editor always edits a grid (a hall without one starts with [start]'s default grid). */
    val layout: SeatLayout = checkNotNull(session.layout) { "The editor always has a layout" }

    val rowCount: Int get() = layout.rowCount
    val columnCount: Int get() = layout.columnCount

    /** Same saved content (sessions are compared by their layout's rows). */
    fun hasSameContentAs(other: LayoutDraft): Boolean =
        layout.rows == other.layout.rows &&
            screenWidth == other.screenWidth &&
            screenDistance == other.screenDistance &&
            screenOffset == other.screenOffset &&
            referenceImagePath == other.referenceImagePath

    /**
     * Paints one cell: [type] SEAT makes it a seat of [kind] (an existing seat
     * keeps its id and only changes kind); GAP and AISLE replace it. Cells outside
     * the grid are ignored.
     */
    fun paint(row: Int, column: Int, type: CellType, kind: SeatKind = SeatKind.STANDARD): LayoutDraft {
        if (row !in 0 until rowCount || column !in 0 until columnCount) return this
        val edited = when (type) {
            CellType.SEAT -> session.setSeat(row, column, kind)
            CellType.GAP, CellType.AISLE -> session.setCellType(row, column, type)
        }
        return if (edited === session) this else copy(session = edited)
    }

    /** Grows (new rows copy the last row's cells) or shrinks at the back, within [1, MAX_ROWS]. */
    fun withRowCount(count: Int): LayoutDraft {
        val target = count.coerceIn(1, MAX_ROWS)
        var draft = this
        while (draft.rowCount < target) draft = draft.insertRowCopy(draft.rowCount, template = draft.rowCount - 1)
        while (draft.rowCount > target) draft = draft.removeRow(draft.rowCount - 1)
        return draft
    }

    /**
     * Grows (new columns copy the last column's cell types) or shrinks at the
     * right, within [1, MAX_COLUMNS]. A screen exactly as wide as the grid stays
     * as wide as the grid; the screen's offset stays within the narrower grid.
     */
    fun withColumnCount(count: Int): LayoutDraft {
        val target = count.coerceIn(1, MAX_COLUMNS)
        if (target == columnCount) return this
        var edited = session
        while (edited.layout!!.columnCount < target) {
            val last = edited.layout!!.columnCount - 1
            val template = edited.layout!!.rows.map { it.cells[last] }
            edited = edited.insertColumn(last + 1, fill = CellType.GAP)
            template.forEachIndexed { row, cell -> edited = edited.copyCellType(row, last + 1, cell) }
        }
        while (edited.layout!!.columnCount > target) edited = edited.removeColumn(edited.layout!!.columnCount - 1)
        val followsGrid = screenWidth == columnCount.toFloat()
        val width = if (followsGrid) target.toFloat() else screenWidth
        return copy(
            session = edited,
            screenWidth = ScreenRules.width(width, target),
            screenOffset = ScreenOffsetRules.snap(screenOffset, target),
        )
    }

    /**
     * Inserts a new row before [index] with the cell types of row [template] (new
     * seats are standard, without fixed labels) and its numbering direction.
     */
    fun insertRowCopy(index: Int, template: Int): LayoutDraft {
        if (rowCount >= MAX_ROWS) return this
        val templateRow = layout.rows[template.coerceIn(0, rowCount - 1)]
        var edited = session.insertRow(index.coerceIn(0, rowCount), fill = CellType.GAP)
        val at = index.coerceIn(0, rowCount)
        templateRow.cells.forEachIndexed { column, cell -> edited = edited.copyCellType(at, column, cell) }
        edited = edited.updateRow(at) { it.copy(numberingDirection = templateRow.numberingDirection) }
        return copy(session = edited)
    }

    /** Removes a row (not the only one). Its seats disappear; saving warns about seats with data. */
    fun removeRow(index: Int): LayoutDraft =
        if (rowCount <= 1 || index !in 0 until rowCount) this else copy(session = session.removeRow(index))

    /** Changes a row's label, numbering, numbering direction, block starts or offset. */
    fun updateRow(index: Int, transform: (LayoutRow) -> LayoutRow): LayoutDraft =
        if (index !in 0 until rowCount) this else copy(session = session.updateRow(index, transform))

    fun withScreenWidth(value: Float): LayoutDraft = copy(screenWidth = ScreenRules.width(value, columnCount))

    fun withScreenDistance(value: Float): LayoutDraft = copy(screenDistance = ScreenRules.distance(value))

    /** Moves the screen sideways: snapped to half seats, its centre within the grid (see [ScreenOffsetRules]). */
    fun withScreenOffset(value: Float): LayoutDraft = copy(screenOffset = ScreenOffsetRules.snap(value, columnCount))

    fun withReferenceImage(path: String?): LayoutDraft = copy(referenceImagePath = path)

    companion object {
        const val DEFAULT_ROWS = 10
        const val DEFAULT_COLUMNS = 16
        const val DEFAULT_SCREEN_DISTANCE = 2f
        const val MAX_ROWS = 50
        const val MAX_COLUMNS = 60

        /**
         * The draft for [hall] as stored. A hall without a (readable) layout starts
         * with a [DEFAULT_ROWS] × [DEFAULT_COLUMNS] grid of new seats (ids from the
         * hall's mark); missing screen geometry starts as wide as the grid and
         * [DEFAULT_SCREEN_DISTANCE] rows away. The stored offset is kept (made
         * valid if it isn't; the steppers then keep it within the grid).
         */
        fun start(hall: Hall): LayoutDraft {
            val started = hall.startLayoutEdit()
            val session = if (started.layout == null) started.newGrid(DEFAULT_ROWS, DEFAULT_COLUMNS) else started
            val columns = session.layout!!.columnCount
            return LayoutDraft(
                session = session,
                screenWidth = hall.screenWidth?.takeIf { it > 0f } ?: columns.toFloat(),
                screenDistance = hall.screenDistance?.takeIf { it >= 0f } ?: DEFAULT_SCREEN_DISTANCE,
                referenceImagePath = hall.referenceImagePath,
                screenOffset = ScreenOffsetRules.snap(hall.screenOffset),
            )
        }
    }
}

private fun LayoutEditSession.copyCellType(row: Int, column: Int, template: LayoutCell): LayoutEditSession =
    when (template) {
        is LayoutCell.Seat -> setSeat(row, column, SeatKind.STANDARD)
        LayoutCell.Aisle -> setCellType(row, column, CellType.AISLE)
        LayoutCell.Gap -> setCellType(row, column, CellType.GAP)
    }

/** Bounds, steps and texts of the screen geometry in the editor (spec §4.2, §6.5). */
object ScreenRules {
    /** Step of the steppers and the drag handles: half a seat / half a row. */
    const val STEP = 0.5f
    const val MIN_WIDTH = 1f
    const val MIN_DISTANCE = 0f
    const val MAX_DISTANCE = 15f

    /** The screen may be up to twice as wide as the grid. */
    fun maxWidth(columns: Int): Float = max(columns * 2f, 2f)

    fun snap(value: Float): Float = (value / STEP).roundToInt() * STEP

    fun width(value: Float, columns: Int): Float = snap(value).coerceIn(MIN_WIDTH, maxWidth(columns))

    fun distance(value: Float): Float = snap(value).coerceIn(MIN_DISTANCE, MAX_DISTANCE)

    /** "14 seats", "14.5 seats", "1 seat". */
    fun widthText(width: Float): String {
        val number = if (width % 1f == 0f) width.toInt().toString() else String.format(Locale.US, "%.1f", width)
        return "$number ${if (width == 1f) "seat" else "seats"}"
    }

    /** "2.0 rows", "1.0 row". */
    fun distanceText(distance: Float): String =
        "${String.format(Locale.US, "%.1f", distance)} ${if (distance == 1f) "row" else "rows"}"

    /** "Centred", "1.5 seats left", "2 seats right", "1 seat left" (left = as seen from the seats). */
    fun offsetText(offset: Float): String {
        if (offset == 0f) return "Centred"
        val seats = abs(offset)
        val number = if (seats % 1f == 0f) seats.toInt().toString() else String.format(Locale.US, "%.1f", seats)
        return "$number ${if (seats == 1f) "seat" else "seats"} ${if (offset < 0f) "left" else "right"}"
    }
}

/**
 * One block (seats between aisles) of a row, for the row panel. Numbers follow
 * the row's numbering direction: [firstNumber] is the block's first number in
 * numbering order (its rightmost seat in a right-to-left row).
 */
data class BlockSummary(
    /** The block counted from the left, as stored (the index of `LayoutRow.blockStartNumbers`). */
    val index: Int,
    /** Custom start number, `null` = automatic. */
    val startNumber: Int?,
    val firstNumber: Int?,
    val lastNumber: Int?,
    /** Fixed seat labels ("R") of the block, in numbering order; they use up no number. */
    val fixedLabels: List<String> = emptyList(),
) {
    /** "1–4", "5", "1–6 + R", "R". */
    val rangeText: String
        get() {
            val numbers = when {
                firstNumber == null -> null
                firstNumber == lastNumber -> "$firstNumber"
                else -> "$firstNumber–$lastNumber"
            }
            val fixed = fixedLabels.joinToString(", ").ifEmpty { null }
            return listOfNotNull(numbers, fixed).joinToString(" + ").ifEmpty { "–" }
        }
}

/** The selected row's settings and derived labels (the editor's row panel). */
data class RowSummary(
    val index: Int,
    /** The row's label as shown (custom or automatic); `null` for a row without seats or label. */
    val label: String?,
    val customLabel: String?,
    /** What the row would be called without a custom label. */
    val autoLabel: String?,
    val numbering: SeatNumbering,
    val halfSeatOffset: Boolean,
    /** The row's blocks from the left, as stored ([BlockSummary.index] = position). */
    val blocks: List<BlockSummary>,
    val numberingDirection: NumberingDirection = NumberingDirection.LEFT_TO_RIGHT,
) {
    /** [blocks] in numbering order: the "Starts at" fields follow the direction. */
    val blocksInNumberingOrder: List<BlockSummary>
        get() = if (numberingDirection == NumberingDirection.RIGHT_TO_LEFT) blocks.asReversed() else blocks

    /** "Row 3 · 3 blocks", "Row 4 · no seats". */
    val title: String
        get() {
            val blockText = when (blocks.size) {
                0 -> "no seats"
                1 -> "1 block"
                else -> "${blocks.size} blocks"
            }
            return "Row ${label ?: "–"} · $blockText"
        }
}

fun rowSummary(layout: SeatLayout, labels: LayoutLabels, index: Int): RowSummary {
    val row = layout.rows[index]
    val hasSeats = row.cells.any { it is LayoutCell.Seat }
    val autoLabel = if (hasSeats) {
        layout.rows.take(index + 1).count { r -> r.cells.any { it is LayoutCell.Seat } }.toString()
    } else {
        null
    }
    val rightToLeft = row.numberingDirection == NumberingDirection.RIGHT_TO_LEFT
    val blocks = labels.seats.values
        .filter { it.position.row == index }
        .groupBy { it.block }
        .toSortedMap()
        .map { (block, seats) ->
            val inOrder = seats.sortedBy { it.position.column }.let { if (rightToLeft) it.asReversed() else it }
            val numbers = inOrder.mapNotNull { it.number }
            BlockSummary(
                index = block,
                startNumber = row.blockStartNumbers.getOrNull(block),
                firstNumber = numbers.firstOrNull(),
                lastNumber = numbers.lastOrNull(),
                fixedLabels = inOrder.filter { it.number == null }.map { it.label.seat },
            )
        }
    return RowSummary(
        index = index,
        label = labels.rowLabels[index],
        customLabel = row.label,
        autoLabel = autoLabel,
        numbering = row.numbering,
        halfSeatOffset = row.halfSeatOffset,
        blocks = blocks,
        numberingDirection = row.numberingDirection,
    )
}

/**
 * Where the reference screenshot lies under the grid, in grid units (1 = one
 * column pitch) relative to the top-left corner of cell (0, 0). Kept for the
 * editor session only (the hall stores just the image).
 */
data class ReferenceAlignment(val left: Float, val top: Float, val width: Float)
