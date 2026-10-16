package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.model.CellPosition
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.SeatKind

/**
 * One paint stroke of the layout editor (spec §6.5), painted so that its result
 * doesn't depend on the direction the finger moves (spec §4.3, see
 * [LayoutDraft.strokeOrder]), at the cost of the rows a move event touches.
 *
 * The result is always [base] with the stroke's cells painted one by one: row by
 * row in the order the stroke first entered them, each row's cells in
 * [LayoutDraft.strokeOrder]. Rows don't affect each other's numbers (only the ids
 * of new seats depend on the order of the rows), so a move event repaints only
 * from the first row whose cells it changes, starting from the draft kept before
 * that row; usually that is just the row under the finger.
 */
internal class PaintStroke(private val base: LayoutDraft, private val type: CellType, private val kind: SeatKind) {
    /** The rows of the stroke, in the order it entered them. */
    private var rows: List<Int> = emptyList()

    /** The stroke's cells of each row, in [LayoutDraft.strokeOrder]. */
    private var cells: Map<Int, List<CellPosition>> = emptyMap()

    /** `drafts[i]`: [base] with the cells of `rows[0 until i]` painted; the last one is the result. */
    private var drafts: List<LayoutDraft> = listOf(base)

    val result: LayoutDraft get() = drafts.last()

    /**
     * Adds [newCells] to the stroke and returns the new [result]. If painting fails
     * (e.g. [io.github.katznmeik.cinexperience.core.model.SeatIdsExhaustedException]),
     * the stroke stays as it was.
     */
    fun add(newCells: List<CellPosition>): LayoutDraft {
        val rows = rows.toMutableList()
        val cells = cells.toMutableMap()
        var firstChanged = rows.size
        for ((row, group) in newCells.groupBy { it.row }) {
            val old = cells[row].orEmpty()
            val merged = base.strokeOrder(old + group, type)
            if (merged == old) continue
            val index = rows.indexOf(row).takeIf { it >= 0 } ?: rows.size.also { rows += row }
            cells[row] = merged
            firstChanged = minOf(firstChanged, index)
        }
        if (firstChanged == rows.size) return result
        val drafts = drafts.subList(0, firstChanged + 1).toMutableList()
        for (index in firstChanged until rows.size) drafts += drafts.last().paintOrdered(cells.getValue(rows[index]), type, kind)
        this.rows = rows
        this.cells = cells
        this.drafts = drafts
        return result
    }
}
