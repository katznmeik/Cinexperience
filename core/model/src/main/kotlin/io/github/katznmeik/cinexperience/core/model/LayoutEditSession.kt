package io.github.katznmeik.cinexperience.core.model

/**
 * One editing session of a hall's layout in the layout editor (spec §6.5). This
 * is the API the editor should use instead of building layouts by hand: it is
 * immutable (every operation returns a new session, so it fits a `StateFlow`) and
 * keeps seat ids correct.
 *
 * - **New seats never reuse ids.** All new seats, including whole new grids
 *   ([newGrid]) after [clear], get ids at or above the hall's id mark
 *   ([Hall.nextSeatId]) and above everything handed out earlier in the session.
 * - **Toggling restores ids.** When a cell that held one of the *original*
 *   layout's seats is turned into a gap or aisle and later back into a seat (at
 *   the same physical position, which follows inserted and removed rows and
 *   columns), it gets its original id back, so its visits stay attached, and its
 *   [LayoutCell.Seat.fixedLabel] (e.g. "R") with it: the editor can't create
 *   fixed labels, so a label lost this way could never be set again. It gets the
 *   painted kind. The block start number keyed on it (the seat was its block's
 *   first numbered seat) comes back with it as well, see below.
 * - **Row settings stay with their rows.** Inserting or removing rows and
 *   columns keeps every row's label, numbering, numbering direction, block starts
 *   and offset, and every seat's kind and fixed label.
 * - **Block starts stay with their seats** ([LayoutRow.withBlockStartsKeyedOn]):
 *   when painting or removing aisles splits or merges a row's blocks, a start
 *   stays with the block that still holds the first numbered seat it was set
 *   for; a split-off part counts on (or restarts, per the row's numbering); a
 *   start whose seat is painted away is dropped, and restored with the seat.
 * - [removedSeatsWithData] gives the §6.5 warning for the current state.
 *
 * Start with [Hall.startLayoutEdit] (or [start]) on the hall as currently stored,
 * and save [layout] with `HallRepository.updateHall(hall.copy(layout = session.layout))`.
 */
class LayoutEditSession private constructor(
    /** The layout as stored when the session started. */
    val original: SeatLayout?,
    /** The layout being edited; `null` when cleared. */
    val layout: SeatLayout?,
    /** Lowest id that may be handed out to a new seat. */
    private val freshIdFloor: Int,
    /** Original seats whose cell is currently a gap or aisle, by current position. */
    private val ghosts: Map<CellPosition, Ghost>,
) {
    private val originalIds: Set<SeatId> = original?.seatIds.orEmpty()

    val hasChanges: Boolean get() = layout?.rows != original?.rows

    /** Replaces the layout with a fresh `rows × columns` grid; all its seats are new. */
    fun newGrid(rows: Int, columns: Int, fill: CellType = CellType.SEAT): LayoutEditSession =
        copyWith(SeatLayout.create(rows, columns, firstSeatId = freshIdFloor, fill = fill), emptyMap())

    /** Removes the layout (the hall shows "Draw seat layout" again). */
    fun clear(): LayoutEditSession = copyWith(null, emptyMap())

    fun setCellType(row: Int, column: Int, type: CellType): LayoutEditSession {
        val current = requireLayout()
        val cell = current.cell(row, column)
        if (cell.type == type) return this
        val position = CellPosition(row, column)
        val ghosts = ghosts.toMutableMap()
        val edited = when {
            type == CellType.SEAT -> {
                val ghost = ghosts.remove(position)
                if (ghost != null && ghost.id !in current.seatIds) {
                    current.withSeat(row, column, ghost.id, fixedLabel = ghost.fixedLabel, blockStart = ghost.blockStart)
                } else {
                    current.withCellType(row, column, type)
                }
            }
            cell is LayoutCell.Seat -> {
                if (cell.id in originalIds) {
                    ghosts[position] = Ghost(cell.id, cell.fixedLabel, current.rows[row].blockStartAnchors()[cell.id])
                }
                current.withCellType(row, column, type)
            }
            else -> current.withCellType(row, column, type) // gap <-> aisle keeps a ghost
        }
        return copyWith(edited, ghosts)
    }

    /**
     * Makes the cell a seat of [kind]. An existing seat keeps its id and only
     * changes its kind (it is the same seat); a gap or aisle becomes a seat as
     * with [setCellType] (getting its original id back where possible).
     */
    fun setSeat(row: Int, column: Int, kind: SeatKind): LayoutEditSession {
        val current = requireLayout()
        val cell = current.cell(row, column)
        if (cell is LayoutCell.Seat) {
            return if (cell.kind == kind) this else copyWith(current.withSeatKind(row, column, kind), ghosts)
        }
        val seated = setCellType(row, column, CellType.SEAT)
        return seated.copyWith(seated.requireLayout().withSeatKind(row, column, kind), seated.ghosts)
    }

    /** SEAT → GAP → AISLE → SEAT. */
    fun cycleCell(row: Int, column: Int): LayoutEditSession =
        setCellType(row, column, requireLayout().cell(row, column).type.next())

    /** Changes a row's label, numbering, numbering direction, block starts or offset (not its cells). */
    fun updateRow(index: Int, transform: (LayoutRow) -> LayoutRow): LayoutEditSession =
        copyWith(requireLayout().updateRow(index, transform), ghosts)

    fun insertRow(index: Int, fill: CellType = CellType.SEAT): LayoutEditSession =
        copyWith(
            requireLayout().insertRow(index, fill),
            ghosts.mapKeys { (p, _) -> if (p.row >= index) p.copy(row = p.row + 1) else p },
        )

    fun removeRow(index: Int): LayoutEditSession =
        copyWith(
            requireLayout().removeRow(index),
            ghosts.filterKeys { it.row != index }.mapKeys { (p, _) -> if (p.row > index) p.copy(row = p.row - 1) else p },
        )

    fun insertColumn(index: Int, fill: CellType = CellType.SEAT): LayoutEditSession =
        copyWith(
            requireLayout().insertColumn(index, fill),
            ghosts.mapKeys { (p, _) -> if (p.column >= index) p.copy(column = p.column + 1) else p },
        )

    fun removeColumn(index: Int): LayoutEditSession =
        copyWith(
            requireLayout().removeColumn(index),
            ghosts.filterKeys { it.column != index }
                .mapKeys { (p, _) -> if (p.column > index) p.copy(column = p.column - 1) else p },
        )

    /** Grows or shrinks the grid at the bottom / right edge. */
    fun resize(rowCount: Int, columnCount: Int, fill: CellType = CellType.SEAT): LayoutEditSession {
        require(rowCount > 0 && columnCount > 0) { "Layout must be at least 1×1" }
        var session = this
        while (session.requireLayout().columnCount < columnCount) session = session.insertColumn(session.layout!!.columnCount, fill)
        while (session.requireLayout().columnCount > columnCount) session = session.removeColumn(session.layout!!.columnCount - 1)
        while (session.requireLayout().rowCount < rowCount) session = session.insertRow(session.layout!!.rowCount, fill)
        while (session.requireLayout().rowCount > rowCount) session = session.removeRow(session.layout!!.rowCount - 1)
        return session
    }

    /** Seats of [original] with data that the current [layout] would remove (spec §6.5). */
    fun removedSeatsWithData(seatsWithData: Set<SeatId>): List<RemovedSeat> =
        removedSeatsWithData(original, layout, seatsWithData)

    private fun requireLayout(): SeatLayout = checkNotNull(layout) { "No layout; call newGrid first" }

    private fun copyWith(newLayout: SeatLayout?, newGhosts: Map<CellPosition, Ghost>): LayoutEditSession =
        LayoutEditSession(
            original = original,
            layout = newLayout,
            freshIdFloor = maxOf(freshIdFloor, newLayout?.nextSeatId ?: 0),
            ghosts = newGhosts,
        )

    /**
     * An original seat painted over: its id, fixed label and the block start keyed
     * on it (`null` = none), restored when the cell becomes a seat again.
     */
    private data class Ghost(val id: SeatId, val fixedLabel: String?, val blockStart: Int?)

    companion object {
        /**
         * Starts a session on [original] (the hall's stored layout, may be `null`)
         * for a hall whose id mark is [hallNextSeatId].
         */
        fun start(original: SeatLayout?, hallNextSeatId: Int): LayoutEditSession {
            val floor = maxOf(hallNextSeatId, original?.nextSeatId ?: SeatLayout.FIRST_SEAT_ID)
            // Raise the layout's own counter to the hall mark, so cells that become
            // seats via SeatLayout's editing functions also get fresh ids.
            val layout = original?.copy(nextSeatId = floor)
            return LayoutEditSession(original, layout, floor, emptyMap())
        }
    }
}
