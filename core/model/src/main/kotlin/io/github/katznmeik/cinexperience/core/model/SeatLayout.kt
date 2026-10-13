package io.github.katznmeik.cinexperience.core.model

/**
 * Stable identity of one seat inside one hall's [SeatLayout] (spec §4.3, §4.4, §4.6).
 *
 * ## Why a stored id instead of (row, column) or the printed label
 *
 * Visits and seat overrides must keep pointing at the *same physical seat* while
 * the owner refines a layout: adding a row in front shifts every grid index,
 * relabelling rows ("1" → "A") or changing block start numbers changes every
 * printed label. Neither grid indices nor labels are therefore stable keys.
 *
 * Instead, every `SEAT` cell carries a [SeatId] that is stored inside the layout
 * itself. The id is allocated from [SeatLayout.nextSeatId] when a cell *becomes*
 * a seat and moves with the cell when rows or columns are inserted or removed
 * around it. Ids are positive, unique within a layout and **never reused**:
 * `nextSeatId` only grows, so a seat that is deleted and later redrawn gets a new
 * id and old visits do not silently re-attach to a different seat.
 *
 * Consequences:
 * - Visits and overrides store `(hallId, seatId)`; display labels are derived on
 *   demand with [SeatLayout.labels].
 * - A seat "disappears" exactly when its id is no longer in the layout, which
 *   makes the §6.5 warning a simple set difference ([removedSeatsWithData]).
 * - The ids are only meaningful within one hall; they are not global keys.
 */
@JvmInline
value class SeatId(val value: Int) {
    init {
        require(value > 0) { "SeatId must be positive, was $value" }
    }

    override fun toString(): String = "SeatId($value)"
}

/** The three kinds of grid cell (spec §4.3). */
enum class CellType {
    SEAT,
    GAP,
    AISLE,
    ;

    /** The next type in the editor's tap cycle SEAT → GAP → AISLE → SEAT (spec §6.5). */
    fun next(): CellType = when (this) {
        SEAT -> GAP
        GAP -> AISLE
        AISLE -> SEAT
    }
}

/** One cell of the layout grid. Only [Seat] cells carry an identity. */
sealed interface LayoutCell {
    val type: CellType

    data class Seat(val id: SeatId) : LayoutCell {
        override val type: CellType get() = CellType.SEAT
    }

    /** Empty space, e.g. the missing ends of a short row. Does not split a row into blocks. */
    data object Gap : LayoutCell {
        override val type: CellType get() = CellType.GAP
    }

    /** A walkway. Splits a row into blocks for seat numbering (spec §4.3 "split rows"). */
    data object Aisle : LayoutCell {
        override val type: CellType get() = CellType.AISLE
    }
}

/** How seat numbers run across the blocks of a split row (spec §4.3). */
enum class SeatNumbering {
    /** Numbers continue across aisles: 1 2 3 | 4 5 6. */
    CONTINUOUS,

    /** Every block starts again: 1 2 3 | 1 2 3. */
    RESTART_PER_BLOCK,
}

/**
 * One grid row.
 *
 * @property label custom row label (e.g. "A"); `null` means auto-numbered.
 * @property blockStartNumbers optional start number per block (index = block index,
 *   counted left to right); a `null` or missing entry means "automatic". See [labels].
 * @property halfSeatOffset the whole row is drawn shifted half a seat to the right
 *   (staggered rows). Alternating rows with and without the offset model staggering.
 */
data class LayoutRow(
    val cells: List<LayoutCell>,
    val label: String? = null,
    val numbering: SeatNumbering = SeatNumbering.CONTINUOUS,
    val blockStartNumbers: List<Int?> = emptyList(),
    val halfSeatOffset: Boolean = false,
) {
    init {
        require(label == null || label.isNotBlank()) { "Row label must be null or non-blank" }
        require(blockStartNumbers.all { it == null || it >= 0 }) {
            "Block start numbers must not be negative: $blockStartNumbers"
        }
    }

    val seatIds: List<SeatId> get() = cells.mapNotNull { (it as? LayoutCell.Seat)?.id }
}

/** A cell position in the grid; row 0 is the row closest to the screen. */
data class CellPosition(val row: Int, val column: Int)

/**
 * A hall's seat grid of `rowCount × columnCount` cells (spec §4.3). The screen is
 * always at the top, i.e. in front of row 0.
 *
 * Instances are immutable and validated: every row has the same width, seat ids
 * are unique and all smaller than [nextSeatId]. Use the editing functions below
 * (they keep seat ids stable, see [SeatId]) instead of building rows by hand
 * whenever an existing layout is modified.
 */
data class SeatLayout(
    val rows: List<LayoutRow>,
    /** Next id to hand out. Strictly greater than every id ever used in this layout. */
    val nextSeatId: Int,
) {
    val rowCount: Int get() = rows.size
    val columnCount: Int get() = rows.first().cells.size

    /** Seat id → grid position, in row-major order. */
    val seatPositions: Map<SeatId, CellPosition>

    init {
        require(rows.isNotEmpty()) { "A layout needs at least one row" }
        val width = rows.first().cells.size
        require(width > 0) { "A layout needs at least one column" }
        val positions = LinkedHashMap<SeatId, CellPosition>()
        rows.forEachIndexed { r, row ->
            require(row.cells.size == width) { "Row $r has ${row.cells.size} cells, expected $width" }
            row.cells.forEachIndexed { c, cell ->
                if (cell is LayoutCell.Seat) {
                    require(positions.put(cell.id, CellPosition(r, c)) == null) { "Duplicate ${cell.id}" }
                    require(cell.id.value < nextSeatId) { "${cell.id} is not below nextSeatId $nextSeatId" }
                }
            }
        }
        seatPositions = positions
    }

    val seatIds: Set<SeatId> get() = seatPositions.keys
    val seatCount: Int get() = seatPositions.size

    fun cell(row: Int, column: Int): LayoutCell = rows[row].cells[column]

    fun positionOf(seatId: SeatId): CellPosition? = seatPositions[seatId]

    // ---- Editing (all keep existing seat ids attached to their cells) ----

    /** Sets one cell's type. A cell that becomes a seat gets a fresh id; an unchanged type is a no-op. */
    fun withCellType(row: Int, column: Int, type: CellType): SeatLayout {
        if (cell(row, column).type == type) return this
        val allocator = IdAllocator(nextSeatId)
        val newCell = allocator.cell(type)
        val newRows = rows.toMutableList()
        newRows[row] = rows[row].copy(cells = rows[row].cells.toMutableList().also { it[column] = newCell })
        return SeatLayout(newRows, allocator.next)
    }

    /** Cycles one cell SEAT → GAP → AISLE → SEAT (spec §6.5). */
    fun cycleCell(row: Int, column: Int): SeatLayout = withCellType(row, column, cell(row, column).type.next())

    /**
     * Changes a row's label, numbering, block start numbers or offset. The
     * transform must leave [LayoutRow.cells] untouched; use [withCellType] for cells.
     */
    fun updateRow(index: Int, transform: (LayoutRow) -> LayoutRow): SeatLayout {
        val old = rows[index]
        val new = transform(old)
        require(new.cells == old.cells) { "updateRow must not change cells; use withCellType" }
        return copy(rows = rows.toMutableList().also { it[index] = new })
    }

    /** Inserts a new row before [index] (`index == rowCount` appends), filled with [fill]. */
    fun insertRow(index: Int, fill: CellType = CellType.SEAT): SeatLayout {
        require(index in 0..rowCount) { "Row index $index out of 0..$rowCount" }
        val allocator = IdAllocator(nextSeatId)
        val row = LayoutRow(List(columnCount) { allocator.cell(fill) })
        return SeatLayout(rows.toMutableList().also { it.add(index, row) }, allocator.next)
    }

    /** Removes the row at [index]. Its seats disappear; the last row cannot be removed. */
    fun removeRow(index: Int): SeatLayout {
        require(rowCount > 1) { "Cannot remove the only row" }
        return copy(rows = rows.toMutableList().also { it.removeAt(index) })
    }

    /** Inserts a new column before [index] (`index == columnCount` appends), filled with [fill]. */
    fun insertColumn(index: Int, fill: CellType = CellType.SEAT): SeatLayout {
        require(index in 0..columnCount) { "Column index $index out of 0..$columnCount" }
        val allocator = IdAllocator(nextSeatId)
        val newRows = rows.map { row ->
            row.copy(cells = row.cells.toMutableList().also { it.add(index, allocator.cell(fill)) })
        }
        return SeatLayout(newRows, allocator.next)
    }

    /** Removes the column at [index]. Its seats disappear; the last column cannot be removed. */
    fun removeColumn(index: Int): SeatLayout {
        require(columnCount > 1) { "Cannot remove the only column" }
        return copy(rows = rows.map { row -> row.copy(cells = row.cells.toMutableList().also { it.removeAt(index) }) })
    }

    /** Grows or shrinks the grid at the bottom / right edge; new cells are [fill]. */
    fun resize(rowCount: Int, columnCount: Int, fill: CellType = CellType.SEAT): SeatLayout {
        require(rowCount > 0 && columnCount > 0) { "Layout must be at least 1×1" }
        var layout = this
        while (layout.columnCount < columnCount) layout = layout.insertColumn(layout.columnCount, fill)
        while (layout.columnCount > columnCount) layout = layout.removeColumn(layout.columnCount - 1)
        while (layout.rowCount < rowCount) layout = layout.insertRow(layout.rowCount, fill)
        while (layout.rowCount > rowCount) layout = layout.removeRow(layout.rowCount - 1)
        return layout
    }

    companion object {
        /** A new `rows × columns` layout filled with [fill]; seat ids are assigned row-major from 1. */
        fun create(rows: Int, columns: Int, fill: CellType = CellType.SEAT): SeatLayout {
            require(rows > 0 && columns > 0) { "Layout must be at least 1×1" }
            val allocator = IdAllocator(FIRST_SEAT_ID)
            return SeatLayout(List(rows) { LayoutRow(List(columns) { allocator.cell(fill) }) }, allocator.next)
        }

        /**
         * Builds a layout from compact row patterns (see [LayoutPattern]), assigning
         * seat ids row-major from 1. Handy for seed data and tests:
         * `SeatLayout.fromPatterns("SSS|SSS", ".SS|SS.")`.
         */
        fun fromPatterns(vararg patterns: String): SeatLayout = fromPatterns(patterns.toList())

        fun fromPatterns(patterns: List<String>): SeatLayout {
            var id = FIRST_SEAT_ID
            val rows = patterns.map { pattern ->
                val ids = List(LayoutPattern.seatCount(pattern)) { SeatId(id++) }
                LayoutRow(LayoutPattern.decode(pattern, ids))
            }
            return SeatLayout(rows, id)
        }

        const val FIRST_SEAT_ID: Int = 1
    }
}

private class IdAllocator(var next: Int) {
    fun cell(type: CellType): LayoutCell = when (type) {
        CellType.SEAT -> LayoutCell.Seat(SeatId(next++))
        CellType.GAP -> LayoutCell.Gap
        CellType.AISLE -> LayoutCell.Aisle
    }
}

/**
 * Compact text form of one row's cells, used by the stored layout JSON and the
 * seed asset: `S` = seat, `.` = gap, `|` = aisle. Example: `".SSS|SSSS|SSS."`.
 */
object LayoutPattern {
    const val SEAT: Char = 'S'
    const val GAP: Char = '.'
    const val AISLE: Char = '|'

    fun encode(cells: List<LayoutCell>): String = buildString(cells.size) {
        cells.forEach {
            append(
                when (it) {
                    is LayoutCell.Seat -> SEAT
                    LayoutCell.Gap -> GAP
                    LayoutCell.Aisle -> AISLE
                },
            )
        }
    }

    fun seatCount(pattern: String): Int = pattern.count { it == SEAT }

    /** Decodes [pattern], giving its seats the [seatIds] in left-to-right order. */
    fun decode(pattern: String, seatIds: List<SeatId>): List<LayoutCell> {
        require(seatIds.size == seatCount(pattern)) {
            "Pattern '$pattern' has ${seatCount(pattern)} seats but ${seatIds.size} ids were given"
        }
        val ids = seatIds.iterator()
        return pattern.map { char ->
            when (char) {
                SEAT -> LayoutCell.Seat(ids.next())
                GAP -> LayoutCell.Gap
                AISLE -> LayoutCell.Aisle
                else -> throw IllegalArgumentException("Unknown cell '$char' in pattern '$pattern'")
            }
        }
    }
}
