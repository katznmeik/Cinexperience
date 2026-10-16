package io.github.katznmeik.cinexperience.core.model

/*
 * Editing helpers only the tests use (the editor paints cells and sets the grid
 * size with its own operations). core:data's tests have their own copy of
 * hasChanges and LayoutEditSession.cycleCell, since this module's tests can't
 * use core:testing.
 */

/** Whether the session's layout differs from the one it started with (rows compared). */
internal val LayoutEditSession.hasChanges: Boolean get() = layout?.rows != original?.rows

/** The next type in the cycle SEAT → GAP → AISLE → SEAT. */
internal fun CellType.next(): CellType = when (this) {
    CellType.SEAT -> CellType.GAP
    CellType.GAP -> CellType.AISLE
    CellType.AISLE -> CellType.SEAT
}

/** Cycles one cell SEAT → GAP → AISLE → SEAT. */
internal fun SeatLayout.cycleCell(row: Int, column: Int): SeatLayout = withCellType(row, column, cell(row, column).type.next())

/** Grows or shrinks the grid at the bottom / right edge; new cells are [fill]. */
internal fun SeatLayout.resize(rowCount: Int, columnCount: Int, fill: CellType = CellType.SEAT): SeatLayout {
    require(rowCount > 0 && columnCount > 0) { "Layout must be at least 1×1" }
    var layout = this
    while (layout.columnCount < columnCount) layout = layout.insertColumn(layout.columnCount, fill)
    while (layout.columnCount > columnCount) layout = layout.removeColumn(layout.columnCount - 1)
    while (layout.rowCount < rowCount) layout = layout.insertRow(layout.rowCount, fill)
    while (layout.rowCount > rowCount) layout = layout.removeRow(layout.rowCount - 1)
    return layout
}

/** SEAT → GAP → AISLE → SEAT. */
internal fun LayoutEditSession.cycleCell(row: Int, column: Int): LayoutEditSession =
    setCellType(row, column, requireLayout().cell(row, column).type.next())

/** Grows or shrinks the grid at the bottom / right edge. */
internal fun LayoutEditSession.resize(rowCount: Int, columnCount: Int, fill: CellType = CellType.SEAT): LayoutEditSession {
    require(rowCount > 0 && columnCount > 0) { "Layout must be at least 1×1" }
    var session = this
    while (session.requireLayout().columnCount < columnCount) session = session.insertColumn(session.requireLayout().columnCount, fill)
    while (session.requireLayout().columnCount > columnCount) session = session.removeColumn(session.requireLayout().columnCount - 1)
    while (session.requireLayout().rowCount < rowCount) session = session.insertRow(session.requireLayout().rowCount, fill)
    while (session.requireLayout().rowCount > rowCount) session = session.removeRow(session.requireLayout().rowCount - 1)
    return session
}

/** As the session's own (private) check: the layout, or an [IllegalStateException] when cleared. */
private fun LayoutEditSession.requireLayout(): SeatLayout = checkNotNull(layout) { "No layout; call newGrid first" }
