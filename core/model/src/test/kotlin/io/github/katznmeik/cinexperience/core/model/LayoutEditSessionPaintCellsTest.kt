package io.github.katznmeik.cinexperience.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [LayoutEditSession.paintCells] (one layout build for a whole paint stroke) gives
 * exactly what painting the same cells one by one gives: the same layout, seat
 * ids, id mark and ghosts (original seats painted back later get their ids,
 * fixed labels and block starts back the same way).
 */
class LayoutEditSessionPaintCellsTest {

    /** Rows with starts, both directions, aisles, gaps, a fixed label and every kind. */
    private val stored: SeatLayout = SeatLayout.fromPatterns(
        "..SSSSSS|SSSS..",
        "SSS|..SSSS|.SSW",
        "..FF..CW.SSSSS.",
        "...............",
        ".SSSS|SSSS|SSS.",
        firstSeatId = 1,
    ).updateRow(0) { it.copy(blockStartNumbers = listOf(4, 12)) }
        .updateRow(1) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT, blockStartNumbers = listOf(null, 5)) }
        .withFixedLabel(1, 14, "R")
        .updateRow(2) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT, blockStartNumbers = listOf(3)) }
        .updateRow(4) { it.copy(numbering = SeatNumbering.RESTART_PER_BLOCK, blockStartNumbers = listOf(2, null, 7)) }

    private fun oneByOne(session: LayoutEditSession, cells: List<CellPosition>, type: CellType, kind: SeatKind) =
        cells.fold(session) { s, (row, column) -> if (type == CellType.SEAT) s.setSeat(row, column, kind) else s.setCellType(row, column, type) }

    /** The same layout, and the same ghosts: every cell painted back as a seat gives the same layout again. */
    private fun assertSameState(expected: LayoutEditSession, actual: LayoutEditSession, message: String) {
        assertEquals(expected.layout, actual.layout, message)
        val all = (0 until stored.rowCount).flatMap { r -> (0 until stored.columnCount).map { CellPosition(r, it) } }
        assertEquals(oneByOne(expected, all, CellType.SEAT, SeatKind.STANDARD).layout, actual.paintCells(all, CellType.SEAT).layout, message)
    }

    @Test
    fun aBatchEqualsTheSamePaintsOneByOne() {
        val random = Random(35)
        val types = listOf(CellType.SEAT, CellType.GAP, CellType.AISLE)
        repeat(300) { trial ->
            var single = LayoutEditSession.start(stored, hallNextSeatId = 70)
            var batch = single
            repeat(4) { step ->
                val cells = List(random.nextInt(1, 25)) { CellPosition(random.nextInt(stored.rowCount), random.nextInt(stored.columnCount)) }
                val type = types.random(random)
                val kind = SeatKind.entries.random(random)
                single = oneByOne(single, cells, type, kind)
                batch = batch.paintCells(cells, type, kind)
                assertSameState(single, batch, "trial $trial step $step: $type $kind $cells")
            }
        }
    }

    @Test
    fun aBatchWithoutChangesAndOneThatRunsOutOfIdsBehaveLikeSinglePaints() {
        val session = LayoutEditSession.start(stored, hallNextSeatId = 70)
        assertEquals(session.layout, session.paintCells(listOf(CellPosition(0, 2), CellPosition(0, 2)), CellType.SEAT).layout)

        val full = LayoutEditSession.start(stored, hallNextSeatId = SeatLayout.MAX_SEAT_ID)
        assertFailsWith<SeatIdsExhaustedException> { full.setCellType(3, 0, CellType.SEAT) }
        assertFailsWith<SeatIdsExhaustedException> { full.paintCells(listOf(CellPosition(3, 1), CellPosition(3, 0)), CellType.SEAT) }
        // Original seats painted away and back need no new id.
        val away = full.paintCells(listOf(CellPosition(0, 2), CellPosition(0, 3)), CellType.GAP)
        assertEquals(stored.rows, away.paintCells(listOf(CellPosition(0, 3), CellPosition(0, 2)), CellType.SEAT).layout!!.rows)
    }
}
