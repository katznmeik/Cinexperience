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
 * around it. Ids are positive, unique within a layout and **never reused within
 * a hall**: a seat that is deleted and later redrawn gets a new id, so old visits
 * do not silently re-attach to a different seat.
 *
 * Never reusing ids must also hold when a layout is *replaced* (new grid, cleared
 * and redrawn, filled from seed data). The hall therefore keeps its own
 * high-water mark, [Hall.nextSeatId], and the repository rejects a layout whose
 * new seats (ids not in the stored layout) are below that mark. Editors should
 * use [LayoutEditSession], which starts new grids at the hall's mark and also
 * gives a seat its original id back when a cell is toggled away and back again.
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

    /**
     * Whether this layout can be an edit of [previous]: the seats both share keep
     * their relative grid placement. Inserting and removing rows and columns, and
     * changing cells, preserve it; numbering a *new* grid from the same ids does
     * not (e.g. 3×4 → 2×6 moves id 5 from row 2 to row 1 next to id 4), which is
     * how the repository detects a replaced layout that reuses old seat ids.
     *
     * Formally: mapping each shared seat's old row to its new row must give a
     * strictly increasing function, and the same for columns.
     */
    fun isEditOf(previous: SeatLayout): Boolean {
        val shared = seatPositions.keys.filter { it in previous.seatPositions }
        fun monotone(coordinate: (CellPosition) -> Int): Boolean {
            val mapping = HashMap<Int, Int>()
            for (id in shared) {
                val old = coordinate(previous.seatPositions.getValue(id))
                val new = coordinate(seatPositions.getValue(id))
                if (mapping.getOrPut(old) { new } != new) return false
            }
            return mapping.entries.sortedBy { it.key }.zipWithNext().all { (a, b) -> a.value < b.value }
        }
        return monotone { it.row } && monotone { it.column }
    }

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

    /**
     * Turns one non-seat cell into a seat with the given, currently unused [id]
     * (used by [LayoutEditSession] to give a seat its original id back).
     */
    fun withSeat(row: Int, column: Int, id: SeatId): SeatLayout {
        require(cell(row, column) !is LayoutCell.Seat) { "Cell ($row, $column) is already a seat" }
        require(id !in seatPositions) { "$id is already used in this layout" }
        val newRows = rows.toMutableList()
        newRows[row] = rows[row].copy(
            cells = rows[row].cells.toMutableList().also { it[column] = LayoutCell.Seat(id) },
        )
        return SeatLayout(newRows, maxOf(nextSeatId, id.value + 1))
    }

    /**
     * The same layout with every seat id (and [nextSeatId]) increased by [delta] ≥ 0.
     * Used to move seed layouts, whose ids start at 1, above a hall's id mark.
     */
    fun withIdsShiftedBy(delta: Int): SeatLayout {
        require(delta >= 0) { "delta must not be negative" }
        if (delta == 0) return this
        val newRows = rows.map { row ->
            row.copy(cells = row.cells.map { if (it is LayoutCell.Seat) LayoutCell.Seat(SeatId(it.id.value + delta)) else it })
        }
        return SeatLayout(newRows, nextSeatId + delta)
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
        /**
         * A new `rows × columns` layout filled with [fill]; seat ids are assigned
         * row-major from [firstSeatId]. For a hall that already had seats, pass the
         * hall's [Hall.nextSeatId] (or use [LayoutEditSession.newGrid]).
         */
        fun create(rows: Int, columns: Int, firstSeatId: Int, fill: CellType = CellType.SEAT): SeatLayout {
            require(rows > 0 && columns > 0) { "Layout must be at least 1×1" }
            require(firstSeatId >= FIRST_SEAT_ID) { "firstSeatId must be positive" }
            val allocator = IdAllocator(firstSeatId)
            return SeatLayout(List(rows) { LayoutRow(List(columns) { allocator.cell(fill) }) }, allocator.next)
        }

        /**
         * Builds a layout from compact row patterns (see [LayoutPattern]), assigning
         * seat ids row-major from [firstSeatId]. Handy for seed data and tests:
         * `SeatLayout.fromPatterns("SSS|SSS", ".SS|SS.", firstSeatId = 1)`.
         */
        fun fromPatterns(vararg patterns: String, firstSeatId: Int): SeatLayout =
            fromPatterns(patterns.toList(), firstSeatId)

        fun fromPatterns(patterns: List<String>, firstSeatId: Int): SeatLayout {
            require(firstSeatId >= FIRST_SEAT_ID) { "firstSeatId must be positive" }
            var id = firstSeatId
            val rows = patterns.map { pattern ->
                val ids = List(LayoutPattern.seatCount(pattern)) { SeatId(id++) }
                LayoutRow(LayoutPattern.decode(pattern, ids))
            }
            return SeatLayout(rows, id)
        }

        /** First seat id of a hall that never had seats. */
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
