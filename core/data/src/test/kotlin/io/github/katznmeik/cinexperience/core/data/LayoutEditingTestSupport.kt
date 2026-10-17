package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.LayoutEditSession

/*
 * Editing helpers only the tests use (the editor paints cells with its own
 * operations); the same as in core:model's tests.
 */

/** Whether the session's layout differs from the one it started with (rows compared). */
internal val LayoutEditSession.hasChanges: Boolean get() = layout?.rows != original?.rows

/** Cycles one cell SEAT → GAP → AISLE → SEAT. */
internal fun LayoutEditSession.cycleCell(row: Int, column: Int): LayoutEditSession {
    val type = checkNotNull(layout) { "No layout; call newGrid first" }.cell(row, column).type
    val next = when (type) {
        CellType.SEAT -> CellType.GAP
        CellType.GAP -> CellType.AISLE
        CellType.AISLE -> CellType.SEAT
    }
    return setCellType(row, column, next)
}
