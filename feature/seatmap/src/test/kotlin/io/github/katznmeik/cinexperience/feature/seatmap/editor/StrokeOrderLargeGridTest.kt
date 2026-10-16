package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.model.CellPosition
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * A paint stroke in the editor ([PaintStroke]) repaints only the rows a move event
 * changes, and gives exactly the layout of painting its cells one by one: its rows
 * in the order the stroke entered them, each row in [LayoutDraft.strokeOrder],
 * also over a whole 50 × 60 grid; and in that order an earlier block's numbers
 * can't push into a later block's (spec §4.3).
 */
class StrokeOrderLargeGridTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** 50 × 60: rows with gaps and aisles, explicit starts, both directions and both numberings. */
    private val large: SeatLayout = run {
        val patterns = List(SeatLayout.MAX_ROWS) { row ->
            val part = "....SSSSSS|.SSSS..SSSS|SSSS...".let { if (row % 3 == 0) it.replace('|', '.') else it }
            part + part.reversed()
        }
        var layout = SeatLayout.fromPatterns(patterns, firstSeatId = 1)
        for (row in 0 until layout.rowCount) {
            layout = layout.updateRow(row) {
                it.copy(
                    numberingDirection = if (row % 2 == 0) NumberingDirection.LEFT_TO_RIGHT else NumberingDirection.RIGHT_TO_LEFT,
                    numbering = if (row % 4 == 1) SeatNumbering.RESTART_PER_BLOCK else SeatNumbering.CONTINUOUS,
                    blockStartNumbers = it.blocks.indices.map { block -> if ((row + block) % 2 == 0) 3 + 10 * block else null },
                )
            }
        }
        layout
    }

    private fun viewModel(layout: SeatLayout): LayoutEditorViewModel {
        val hall = Hall(id = 1, cinemaId = 1, name = "Saal 1", layout = layout, screenWidth = 20f, screenDistance = 2f)
        return LayoutEditorViewModel(
            1,
            FakeCinexRepository(FakeStore(cinemas = listOf(Cinema(id = 1, name = "Kino")), halls = listOf(hall))),
            FakeReferenceImageStore(),
        )
    }

    private val LayoutEditorViewModel.draft get() = uiState.value.draft!!

    /** A stroke over [rows] as a finger would paint it: snaking, one move event per row. */
    private fun LayoutEditorViewModel.snake(tool: CellType, rows: IntRange = 0 until SeatLayout.MAX_ROWS) {
        selectTool(tool)
        beginGesture()
        for (row in rows) {
            val columns = (0 until SeatLayout.MAX_COLUMNS).let { if (row % 2 == 0) it else it.reversed() }
            paintCells(columns.map { CellPosition(row, it) })
        }
        endGesture()
    }

    /** The canonical result: every cell painted one by one, in [LayoutDraft.strokeOrder]. */
    private fun LayoutDraft.oneByOne(cells: List<CellPosition>, type: CellType): LayoutDraft =
        strokeOrder(cells, type).fold(this) { draft, (row, column) -> draft.paint(row, column, type) }

    private fun allCells(rows: IntRange = 0 until SeatLayout.MAX_ROWS) =
        rows.flatMap { row -> (0 until SeatLayout.MAX_COLUMNS).map { CellPosition(row, it) } }

    @Test
    fun aStrokeOverTheWholeGridGivesTheLayoutOfPaintingItsCellsOneByOne() {
        val vm = viewModel(large)
        val start = vm.draft

        // An eraser over the top half (original seats become ghosts), then seats over everything.
        vm.snake(CellType.GAP, 0 until 25)
        val erased = start.oneByOne(allCells(0 until 25), CellType.GAP)
        assertEquals(erased.layout, vm.draft.layout)

        vm.snake(CellType.SEAT)
        val seated = erased.oneByOne(allCells(), CellType.SEAT)
        assertEquals(seated.layout, vm.draft.layout)
        assertEquals(seated.layout.labels(), vm.draft.layout.labels())

        // The stroke is one undo step.
        vm.undo()
        assertEquals(erased.layout.rows, vm.draft.layout.rows)
    }

    @Test
    fun aStrokeThatGoesUpAndComesBackIntoItsRowsGivesTheSameLayout() {
        val vm = viewModel(large)
        val start = vm.draft
        vm.selectTool(CellType.SEAT)
        vm.beginGesture()
        // Up over the left half, cell by cell, then down over the right half, a row per event.
        for (row in SeatLayout.MAX_ROWS - 1 downTo 0) (0 until 30).forEach { vm.paintCells(listOf(CellPosition(row, it))) }
        for (row in 0 until SeatLayout.MAX_ROWS) vm.paintCells((30 until 60).map { CellPosition(row, it) })
        vm.endGesture()

        val expected = (SeatLayout.MAX_ROWS - 1 downTo 0).fold(start) { draft, row ->
            draft.oneByOne((0 until 60).map { CellPosition(row, it) }, CellType.SEAT)
        }
        assertEquals(expected.layout, vm.draft.layout)
        // The rows' order only changes the ids of new seats, not their numbers.
        assertEquals(
            start.oneByOne(allCells(), CellType.SEAT).layout.labels().seats.values.map { it.position to it.label },
            vm.draft.layout.labels().seats.values.map { it.position to it.label },
        )
    }

    @Test
    fun anEarlierBlocksFallbackDoesNotPushIntoALaterBlocksNumbers() {
        // 1 2 | 3 4 | 6 7: one stroke in front of the middle and the right block, which both have
        // no room below their start. The middle block is done first, so the right one follows on.
        val row = SeatLayout.fromPatterns("SS|.SS|..SS", firstSeatId = 1)
            .updateRow(0) { it.copy(blockStartNumbers = listOf(null, 3, 6)) }
        val texts = listOf(listOf(3, 7, 8), listOf(8, 7, 3), listOf(7, 3, 8)).map { columns ->
            val vm = viewModel(row)
            vm.selectTool(CellType.SEAT)
            vm.beginGesture()
            columns.forEach { vm.paintCells(listOf(CellPosition(0, it))) }
            vm.endGesture()
            vm.draft.layout.let { layout -> layout.rows[0].seatIds.map { layout.labels().labelOf(it)!!.seat } }
        }
        texts.forEach { assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9"), it) }
    }
}
