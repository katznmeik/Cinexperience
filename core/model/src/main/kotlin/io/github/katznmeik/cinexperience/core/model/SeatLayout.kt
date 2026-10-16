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

/**
 * What kind of place a `SEAT` cell is. Yorck hall plans mark wheelchair spaces,
 * companion seats and sofa places; they are real, rateable seats, and they are
 * numbered like every other seat (spec §4.3, see [labels]). The kind is a
 * property of the seat, not a cell type: changing it keeps the seat's [SeatId].
 *
 * @property patternChar the seat's character in a [LayoutPattern].
 */
enum class SeatKind(val patternChar: Char) {
    STANDARD('S'),

    /** A wheelchair space. */
    WHEELCHAIR('W'),

    /** A companion seat (German "Begleitsitz"). */
    COMPANION('C'),

    /** A sofa place. */
    SOFA('F'),
    ;

    companion object {
        fun fromPatternChar(char: Char): SeatKind? = entries.firstOrNull { it.patternChar == char }
    }
}

/** One cell of the layout grid. Only [Seat] cells carry an identity. */
sealed interface LayoutCell {
    val type: CellType

    /**
     * A seat.
     *
     * @property fixedLabel the seat's label where Yorck doesn't number it (spec §4.3),
     *   e.g. "R" for a wheelchair space: shown instead of a number, and the seat
     *   doesn't use up a number (see [labels]). It belongs to the seat: it moves
     *   with it and survives a change of [kind]. A seat painted as a gap or aisle
     *   and back as a seat in the same [LayoutEditSession] gets its id and fixed
     *   label back; a seat deleted with its row or column loses it (a seat drawn
     *   there again is a new, numbered seat). 1 to [MAX_FIXED_LABEL_LENGTH]
     *   characters, without surrounding spaces, control or format characters.
     *   `null` (the rule) = the seat is numbered.
     */
    data class Seat(
        val id: SeatId,
        val kind: SeatKind = SeatKind.STANDARD,
        val fixedLabel: String? = null,
    ) : LayoutCell {
        init {
            require(fixedLabel == null || isValidFixedLabel(fixedLabel)) {
                "A fixed seat label must have 1–$MAX_FIXED_LABEL_LENGTH characters, no surrounding spaces and no " +
                    "control or format characters, was '$fixedLabel'"
            }
        }

        override val type: CellType get() = CellType.SEAT

        companion object {
            /** Longest [fixedLabel]: it has to fit inside the seat on the map. */
            const val MAX_FIXED_LABEL_LENGTH: Int = 4

            /**
             * 1 to [MAX_FIXED_LABEL_LENGTH] characters, not blank, without surrounding
             * spaces and without ISO control or format (Unicode `Cf`, e.g. zero-width
             * or bidi) characters, which would be invisible or garble the map.
             */
            fun isValidFixedLabel(label: String): Boolean =
                label.isNotBlank() &&
                    label.length <= MAX_FIXED_LABEL_LENGTH &&
                    label == label.trim() &&
                    label.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        }
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
 * The direction in which a row's seats are numbered, as drawn with the screen at
 * the top (spec §4.3). Yorck uses both, even within one cinema.
 */
enum class NumberingDirection {
    /** 1 2 3 4: the leftmost seat comes first. */
    LEFT_TO_RIGHT,

    /** 4 3 2 1: the rightmost seat comes first. */
    RIGHT_TO_LEFT,
}

/**
 * One grid row.
 *
 * @property label custom row label (e.g. "C", "R1"); `null` means auto-numbered;
 *   [NO_NAME] ("-", Yorck's convention) means the row has no name ([hasNoName]).
 * @property blockStartNumbers optional start number per block; a `null` or missing
 *   entry means "automatic". The index is the block counted from the left as
 *   stored, whatever the [numberingDirection]; the value is the number of that
 *   block's first numbered seat in numbering order, i.e. of its rightmost seat
 *   when the row runs right to left. See [labels]. When a change of cell types
 *   splits, merges or shortens blocks, a start stays with the block that holds
 *   the seat it was set on and numbers that block's current first numbered seat;
 *   when that seat is removed, it moves on to the next numbered seat, with the
 *   number that seat had ([withBlockStartsKeyedOn], [withCells]).
 * @property halfSeatOffset the whole row is drawn shifted half a seat to the right
 *   (staggered rows). Alternating rows with and without the offset model staggering.
 * @property numberingDirection which way the row's seats are numbered.
 */
data class LayoutRow(
    val cells: List<LayoutCell>,
    val label: String? = null,
    val numbering: SeatNumbering = SeatNumbering.CONTINUOUS,
    val blockStartNumbers: List<Int?> = emptyList(),
    val halfSeatOffset: Boolean = false,
    val numberingDirection: NumberingDirection = NumberingDirection.LEFT_TO_RIGHT,
) {
    init {
        require(label == null || label.isNotBlank()) { "Row label must be null or non-blank" }
        require(blockStartNumbers.all { it == null || it >= 0 }) {
            "Block start numbers must not be negative: $blockStartNumbers"
        }
    }

    /**
     * The row has no name (spec §4.3, e.g. each double sofa of Kant Kino Saal 3):
     * its stored [label] is [NO_NAME]. Its seats are shown by seat alone ("Seat 12"),
     * the map draws no row label beside it, and it still counts as a seat row for
     * the automatic numbering of the rows after it.
     */
    val hasNoName: Boolean get() = label == NO_NAME

    val seatIds: List<SeatId> get() = cells.mapNotNull { (it as? LayoutCell.Seat)?.id }

    /** The fixed label of each seat (`null` = numbered), aligned with [seatIds]: left to right. */
    val fixedLabels: List<String?> get() = cells.filterIsInstance<LayoutCell.Seat>().map { it.fixedLabel }

    /**
     * The row's blocks (spec §4.3 "split rows") from the left, each as the columns
     * of its seats from the left. Only aisles split a row (gaps don't); leading,
     * trailing and repeated aisles don't create empty blocks.
     */
    val blocks: List<List<Int>>
        get() {
            val blocks = ArrayList<MutableList<Int>>()
            var aisleSinceLastSeat = false
            cells.forEachIndexed { column, cell ->
                when (cell) {
                    LayoutCell.Aisle -> if (blocks.isNotEmpty()) aisleSinceLastSeat = true
                    LayoutCell.Gap -> Unit
                    is LayoutCell.Seat -> {
                        if (blocks.isEmpty() || aisleSinceLastSeat) {
                            blocks += ArrayList<Int>()
                            aisleSinceLastSeat = false
                        }
                        blocks.last() += column
                    }
                }
            }
            return blocks
        }

    /** The seats of each block in numbering order (the rightmost seat first when the row runs right to left). */
    private val blockSeatsInNumberingOrder: List<List<LayoutCell.Seat>>
        get() = blocks.map { columns ->
            val seats = columns.map { cells[it] as LayoutCell.Seat }
            if (numberingDirection == NumberingDirection.RIGHT_TO_LEFT) seats.asReversed() else seats
        }

    /**
     * The seat each block's start number is keyed on, with that start (`null` =
     * automatic): the block's first numbered seat in numbering order, whose number
     * the start is (spec §4.3). Seats with a [LayoutCell.Seat.fixedLabel] use no
     * number and don't count; a block with only such seats (its start is unused)
     * keys it on its first seat, so the stored value is kept as long as that block
     * gets no numbered seat.
     */
    internal fun blockStartAnchors(): Map<SeatId, Int?> {
        val anchors = LinkedHashMap<SeatId, Int?>()
        blockSeatsInNumberingOrder.forEachIndexed { block, seats ->
            val anchor = seats.firstOrNull { it.fixedLabel == null } ?: seats.first()
            anchors[anchor.id] = blockStartNumbers.getOrNull(block)
        }
        return anchors
    }

    /**
     * This row with its [blockStartNumbers] re-keyed after a cell change, so that
     * every start stays with the seat it was keyed on ([blockStartAnchors] of the
     * row before the change, plus any restored seat's start): each block gets the
     * start of the first of its numbered seats, in numbering order, that carries
     * one (a block without numbered seats: of the first of its seats that does).
     *
     * So a split-off part without that seat gets `null` (it counts on with
     * [SeatNumbering.CONTINUOUS] or restarts with [SeatNumbering.RESTART_PER_BLOCK]);
     * when blocks merge, the start of the one whose first numbered seat comes first
     * in numbering order is kept and the other is dropped (the unused start of a
     * block of fixed-label seats never wins against numbered seats); a start whose
     * seat is gone is dropped (unless [withCells] moved it on to another seat); and
     * a row whose blocks hold the same seats as before comes out with the same
     * starts. The list is normalised (no entries beyond the blocks, no trailing
     * `null`s).
     */
    internal fun withBlockStartsKeyedOn(anchors: Map<SeatId, Int?>): LayoutRow {
        val starts = blockSeatsInNumberingOrder.map { seats ->
            val candidates = seats.filter { it.fixedLabel == null }.ifEmpty { seats }
            candidates.firstOrNull { it.id in anchors }?.let { anchors[it.id] }
        }
        return copy(blockStartNumbers = starts.dropLastWhile { it == null })
    }

    /**
     * This row with [newCells] and its block starts re-keyed from this row's seats
     * (see [withBlockStartsKeyedOn]); [restored] adds the start a seat put back by
     * [SeatLayout.withSeat] was keyed on.
     *
     * A block with an explicit start that loses the seat it is keyed on keeps its
     * numbers (spec §4.3: they must match Yorck's booking): the start moves on to
     * the block's next numbered seat in numbering order that is still there, with
     * the number that seat had, so every remaining seat keeps its number. A block
     * left without numbered seats keeps nothing; an automatic block stays automatic.
     *
     * When the change leaves the number of blocks and every block's start as they
     * were, the stored list is kept as it is (even with trailing `null`s or entries
     * beyond the blocks, as older data may hold), so an edit that doesn't touch the
     * row's blocks leaves it identical.
     */
    internal fun withCells(newCells: List<LayoutCell>, restored: Map<SeatId, Int?> = emptyMap()): LayoutRow {
        val changed = copy(cells = newCells)
        val reKeyed = changed.withBlockStartsKeyedOn(blockStartAnchors() + movedStarts(changed) + restored)
        val blockCount = changed.blocks.size
        val sameStarts = blockCount == blocks.size &&
            (0 until blockCount).all { reKeyed.blockStartNumbers.getOrNull(it) == blockStartNumbers.getOrNull(it) }
        return if (sameStarts) changed else reKeyed
    }

    /**
     * For each block with an explicit start whose keyed seat (its first numbered
     * seat in numbering order) [changed] no longer holds: the block's next numbered
     * seat that [changed] still holds, with its number in this row (see [withCells]).
     */
    private fun movedStarts(changed: LayoutRow): Map<SeatId, Int> {
        val remaining = changed.seatIds.toSet()
        val blocks = blocks
        val numbers by lazy { numbersByColumn(blocks) }
        val moved = HashMap<SeatId, Int>()
        blocks.forEachIndexed { block, columns ->
            if (blockStartNumbers.getOrNull(block) == null) return@forEachIndexed
            val inOrder = if (numberingDirection == NumberingDirection.RIGHT_TO_LEFT) columns.asReversed() else columns
            val numbered = inOrder.filter { (cells[it] as LayoutCell.Seat).fixedLabel == null }
            val keyed = numbered.firstOrNull() ?: return@forEachIndexed
            if ((cells[keyed] as LayoutCell.Seat).id in remaining) return@forEachIndexed
            val next = numbered.drop(1).firstOrNull { (cells[it] as LayoutCell.Seat).id in remaining } ?: return@forEachIndexed
            moved[(cells[next] as LayoutCell.Seat).id] = checkNotNull(numbers[next])
        }
        return moved
    }

    companion object {
        /** The stored [label] of a row without a name: "-", as in Yorck's booking data. */
        const val NO_NAME = "-"
    }
}

/** A cell position in the grid; row 0 is the row closest to the screen. */
data class CellPosition(val row: Int, val column: Int)

/**
 * A hall's seat grid of `rowCount × columnCount` cells (spec §4.3). The screen is
 * always at the top, i.e. in front of row 0.
 *
 * Instances are immutable and validated: at most [MAX_ROWS] × [MAX_COLUMNS]
 * cells, every row has the same width, seat ids are unique and all smaller than
 * [nextSeatId], which is at most [MAX_SEAT_ID]. Use the editing functions below
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
        require(rows.size <= MAX_ROWS) { "A layout has at most $MAX_ROWS rows, not ${rows.size}" }
        val width = rows.first().cells.size
        require(width > 0) { "A layout needs at least one column" }
        require(width <= MAX_COLUMNS) { "A layout has at most $MAX_COLUMNS columns, not $width" }
        require(nextSeatId in FIRST_SEAT_ID..MAX_SEAT_ID) { "nextSeatId $nextSeatId is outside $FIRST_SEAT_ID..$MAX_SEAT_ID" }
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

    // ---- Editing (all keep existing seat ids attached to their cells; those that
    // change cell types keep block starts with their blocks: see LayoutRow.withCells) ----

    /** Sets one cell's type. A cell that becomes a seat gets a fresh id; an unchanged type is a no-op. */
    fun withCellType(row: Int, column: Int, type: CellType): SeatLayout {
        if (cell(row, column).type == type) return this
        val allocator = IdAllocator(nextSeatId)
        val newCell = allocator.cell(type)
        val newRows = rows.toMutableList()
        newRows[row] = rows[row].withCells(rows[row].cells.toMutableList().also { it[column] = newCell })
        return SeatLayout(newRows, allocator.next)
    }

    /**
     * Turns one non-seat cell into a seat with the given, currently unused [id]
     * and [fixedLabel] (used by [LayoutEditSession] to give a seat its original id
     * and fixed label back). [blockStart] is the start number the seat was keyed
     * on when it was painted away (see [LayoutRow.blockStartNumbers]). Its block
     * takes it unless a numbered seat that carries a start comes before the
     * restored seat in the block's numbering order; then that seat's start wins
     * and [blockStart] is dropped.
     */
    fun withSeat(
        row: Int,
        column: Int,
        id: SeatId,
        kind: SeatKind = SeatKind.STANDARD,
        fixedLabel: String? = null,
        blockStart: Int? = null,
    ): SeatLayout {
        require(cell(row, column) !is LayoutCell.Seat) { "Cell ($row, $column) is already a seat" }
        require(id !in seatPositions) { "$id is already used in this layout" }
        if (id.value >= MAX_SEAT_ID) throw SeatIdsExhaustedException("$id is not below $MAX_SEAT_ID")
        require(blockStart == null || blockStart >= 0) { "Block start numbers must not be negative: $blockStart" }
        val newCells = rows[row].cells.toMutableList().also { it[column] = LayoutCell.Seat(id, kind, fixedLabel) }
        val restored = if (blockStart == null) emptyMap() else mapOf(id to blockStart)
        val newRows = rows.toMutableList().also { it[row] = rows[row].withCells(newCells, restored) }
        return SeatLayout(newRows, maxOf(nextSeatId, id.value + 1))
    }

    /** Changes the [SeatKind] of the seat at ([row], [column]); the seat keeps its id and fixed label. */
    fun withSeatKind(row: Int, column: Int, kind: SeatKind): SeatLayout {
        val seat = cell(row, column)
        require(seat is LayoutCell.Seat) { "Cell ($row, $column) is not a seat" }
        if (seat.kind == kind) return this
        return copy(rows = replaceCell(row, column, seat.copy(kind = kind)))
    }

    /**
     * Gives the seat at ([row], [column]) a fixed label instead of a number, or its
     * number back with `null` (see [LayoutCell.Seat.fixedLabel]). The seat keeps its id.
     */
    fun withFixedLabel(row: Int, column: Int, label: String?): SeatLayout {
        val seat = cell(row, column)
        require(seat is LayoutCell.Seat) { "Cell ($row, $column) is not a seat" }
        if (seat.fixedLabel == label) return this
        return copy(rows = replaceCell(row, column, seat.copy(fixedLabel = label)))
    }

    private fun replaceCell(row: Int, column: Int, cell: LayoutCell): List<LayoutRow> =
        rows.toMutableList().also { newRows ->
            newRows[row] = rows[row].copy(cells = rows[row].cells.toMutableList().also { it[column] = cell })
        }

    /**
     * The same layout with every seat id (and [nextSeatId]) increased by [delta] ≥ 0.
     * Used to move seed layouts, whose ids start at 1, above a hall's id mark.
     * @throws SeatIdsExhaustedException if [nextSeatId] would pass [MAX_SEAT_ID].
     */
    fun withIdsShiftedBy(delta: Int): SeatLayout {
        require(delta >= 0) { "delta must not be negative" }
        if (delta > MAX_SEAT_ID - nextSeatId) throw SeatIdsExhaustedException("Shifting seat ids by $delta would pass $MAX_SEAT_ID")
        if (delta == 0) return this
        val newRows = rows.map { row ->
            row.copy(cells = row.cells.map { if (it is LayoutCell.Seat) it.copy(id = SeatId(it.id.value + delta)) else it })
        }
        return SeatLayout(newRows, nextSeatId + delta)
    }

    /**
     * This layout with its seat ids renumbered row-major from [FIRST_SEAT_ID] (and
     * [nextSeatId] right after them) and each row's [LayoutRow.blockStartNumbers]
     * normalised (entries beyond the row's blocks and trailing `null`s, which mean
     * nothing for the labels, are dropped); everything else unchanged. Two layouts
     * with equal canonical [rows] have the same structure (cells, seat kinds, fixed
     * labels, row settings) and differ at most in their seat ids, e.g. a seed
     * layout and the same layout shifted above a hall's id mark ([withIdsShiftedBy]).
     */
    fun withCanonicalSeatIds(): SeatLayout {
        var next = FIRST_SEAT_ID
        val newRows = rows.map { row ->
            row.copy(
                cells = row.cells.map { if (it is LayoutCell.Seat) it.copy(id = SeatId(next++)) else it },
                blockStartNumbers = row.blockStartNumbers.take(row.blocks.size).dropLastWhile { it == null },
            )
        }
        return SeatLayout(newRows, next)
    }

    /** Whether [other] has the same structure as this layout, ignoring seat ids (see [withCanonicalSeatIds]). */
    fun hasSameStructureAs(other: SeatLayout): Boolean =
        withCanonicalSeatIds().rows == other.withCanonicalSeatIds().rows

    /** Cycles one cell SEAT → GAP → AISLE → SEAT (spec §6.5). */
    fun cycleCell(row: Int, column: Int): SeatLayout = withCellType(row, column, cell(row, column).type.next())

    /**
     * Changes a row's label, numbering, numbering direction, block start numbers or
     * offset. The transform must leave [LayoutRow.cells] untouched; use
     * [withCellType] for cells.
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
            row.withCells(row.cells.toMutableList().also { it.add(index, allocator.cell(fill)) })
        }
        return SeatLayout(newRows, allocator.next)
    }

    /** Removes the column at [index]. Its seats disappear; the last column cannot be removed. */
    fun removeColumn(index: Int): SeatLayout {
        require(columnCount > 1) { "Cannot remove the only column" }
        return copy(rows = rows.map { row -> row.withCells(row.cells.toMutableList().also { it.removeAt(index) }) })
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
            val allocator = IdAllocator.from(firstSeatId)
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
            val allocator = IdAllocator.from(firstSeatId)
            val rows = patterns.map { pattern ->
                val ids = List(LayoutPattern.seatCount(pattern)) { allocator.seatId() }
                LayoutRow(LayoutPattern.decode(pattern, ids))
            }
            return SeatLayout(rows, allocator.next)
        }

        /** First seat id of a hall that never had seats. */
        const val FIRST_SEAT_ID: Int = 1

        /**
         * Most rows a layout can have: the layout editor's limit (the seed's tallest
         * hall, delphi Filmpalast, has 40). Like [MAX_COLUMNS], it keeps a layout
         * from a damaged or crafted backup small enough to decode, draw and store
         * quickly.
         */
        const val MAX_ROWS: Int = 50

        /** Most columns a layout can have: the layout editor's limit (the seed's widest hall, International, has 34). */
        const val MAX_COLUMNS: Int = 60

        /**
         * Upper bound of [nextSeatId], so seat ids stay below it. Ids rise by one per
         * new seat and a full new grid uses at most [MAX_ROWS] × [MAX_COLUMNS] =
         * 3,000, so real halls stay far below it; it leaves headroom up to
         * [Int.MAX_VALUE], so id arithmetic never overflows. Handing out an id at or
         * above it fails with a [SeatIdsExhaustedException].
         */
        const val MAX_SEAT_ID: Int = 1_000_000_000
    }
}

/**
 * A layout operation needed a seat id at or above [SeatLayout.MAX_SEAT_ID]: the
 * hall's ids are used up. Only damaged or crafted data gets there (a real hall
 * uses a few thousand ids), so the layout editor refuses just that edit; any
 * other [IllegalArgumentException] from a layout operation is a bug.
 */
class SeatIdsExhaustedException(message: String) : IllegalArgumentException(message)

/**
 * Hands out new seat ids from [next] upwards. Never one at or above
 * [SeatLayout.MAX_SEAT_ID]: that fails with a [SeatIdsExhaustedException] instead
 * of wrapping around to a negative id.
 */
private class IdAllocator(var next: Int) {
    fun seatId(): SeatId {
        if (next >= SeatLayout.MAX_SEAT_ID) {
            throw SeatIdsExhaustedException("No seat id left below ${SeatLayout.MAX_SEAT_ID} (next would be $next)")
        }
        return SeatId(next++)
    }

    fun cell(type: CellType): LayoutCell = when (type) {
        CellType.SEAT -> LayoutCell.Seat(seatId())
        CellType.GAP -> LayoutCell.Gap
        CellType.AISLE -> LayoutCell.Aisle
    }

    companion object {
        /** The allocator of a new layout whose ids start at [firstSeatId]. */
        fun from(firstSeatId: Int): IdAllocator {
            require(firstSeatId >= SeatLayout.FIRST_SEAT_ID) { "firstSeatId must be positive" }
            if (firstSeatId > SeatLayout.MAX_SEAT_ID) {
                throw SeatIdsExhaustedException("firstSeatId $firstSeatId is above ${SeatLayout.MAX_SEAT_ID}")
            }
            return IdAllocator(firstSeatId)
        }
    }
}

/**
 * Compact text form of one row's cells, used by the stored layout JSON and the
 * seed asset: `S` = standard seat, `W` = wheelchair space, `C` = companion seat,
 * `F` = sofa place (see [SeatKind.patternChar]), `.` = gap, `|` = aisle.
 * Example: `".SSS|SSSS|SWC."`. Seat ids and fixed labels are stored beside it.
 */
object LayoutPattern {
    const val SEAT: Char = 'S'
    const val GAP: Char = '.'
    const val AISLE: Char = '|'

    fun encode(cells: List<LayoutCell>): String = buildString(cells.size) {
        cells.forEach {
            append(
                when (it) {
                    is LayoutCell.Seat -> it.kind.patternChar
                    LayoutCell.Gap -> GAP
                    LayoutCell.Aisle -> AISLE
                },
            )
        }
    }

    /** Number of seats of any [SeatKind] in [pattern]. */
    fun seatCount(pattern: String): Int = pattern.count { SeatKind.fromPatternChar(it) != null }

    /** Decodes [pattern], giving its seats the [seatIds] in left-to-right order. */
    fun decode(pattern: String, seatIds: List<SeatId>): List<LayoutCell> {
        require(seatIds.size == seatCount(pattern)) {
            "Pattern '$pattern' has ${seatCount(pattern)} seats but ${seatIds.size} ids were given"
        }
        val ids = seatIds.iterator()
        return pattern.map { char ->
            when (char) {
                GAP -> LayoutCell.Gap
                AISLE -> LayoutCell.Aisle
                else -> {
                    val kind = SeatKind.fromPatternChar(char)
                        ?: throw IllegalArgumentException("Unknown cell '$char' in pattern '$pattern'")
                    LayoutCell.Seat(ids.next(), kind)
                }
            }
        }
    }
}
