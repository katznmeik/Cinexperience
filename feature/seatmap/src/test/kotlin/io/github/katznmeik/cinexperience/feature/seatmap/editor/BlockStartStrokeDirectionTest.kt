package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.model.CellPosition
import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.seatmapui.seatNumberText
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * A paint stroke over several cells in front of a block gives the same numbers
 * whichever way the finger moves (spec §4.3 / §6.5): the existing seats keep
 * their numbers, and the stroke is one undo step.
 */
class BlockStartStrokeDirectionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Row 0: Neues Off row 1 style, 4 … 10. Row 1: Kant Kino style, right to left, 10 … 3. */
    private val stored = SeatLayout.fromPatterns(".....SSSSSSS.....", "...SSSSSSSS......", firstSeatId = 1)
        .updateRow(0) { it.copy(blockStartNumbers = listOf(4)) }
        .updateRow(1) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT, blockStartNumbers = listOf(3)) }
    private val hall = Hall(id = 1, cinemaId = 1, name = "Saal 1", layout = stored, screenWidth = 17f, screenDistance = 2f)

    private fun viewModel() = LayoutEditorViewModel(
        1,
        FakeCinexRepository(FakeStore(cinemas = listOf(Cinema(id = 1, name = "Kino")), halls = listOf(hall))),
        FakeReferenceImageStore(),
    )

    private fun LayoutEditorViewModel.numbers(row: Int) = uiState.value.labels!!.seats.values
        .filter { it.position.row == row }
        .sortedBy { it.position.column }
        .map { it.label.seatNumberText }

    /** A stroke over [columns] of [row] in the given order, one move event per cell. */
    private fun LayoutEditorViewModel.stroke(row: Int, columns: List<Int>, tool: CellType = CellType.SEAT) {
        selectTool(tool)
        beginGesture()
        columns.forEach { paintCells(listOf(CellPosition(row, it))) }
        endGesture()
    }

    @Test
    fun seatsPaintedInFrontOfALeftToRightBlockKeepItsNumbersInBothDirections() {
        val expected = listOf("1", "2", "3") + (4..10).map { "$it" }
        for (columns in listOf(listOf(2, 3, 4), listOf(4, 3, 2))) {
            val vm = viewModel()
            vm.stroke(0, columns)
            assertEquals("stroke $columns", expected, vm.numbers(0))
            assertEquals(listOf(1), vm.uiState.value.draft!!.layout.rows[0].blockStartNumbers)

            // One undo step.
            vm.undo()
            assertEquals(stored.rows, vm.uiState.value.draft!!.layout.rows)
            assertFalse(vm.uiState.value.canUndo)
        }
    }

    @Test
    fun seatsPaintedInFrontOfARightToLeftBlockKeepItsNumbersInBothDirections() {
        val expected = (10 downTo 1).map { "$it" }
        val results = listOf(listOf(11, 12), listOf(12, 11)).map { columns ->
            viewModel().apply { stroke(1, columns) }
        }
        results.forEach { assertEquals(expected, it.numbers(1)) }
        assertEquals(results[0].uiState.value.draft!!.layout.rows, results[1].uiState.value.draft!!.layout.rows)
    }

    @Test
    fun aStrokeOverTwoCellsInOneMoveEventAndAnEraserStrokeDoNotDependOnTheOrder() {
        val oneEvent = listOf(listOf(2, 3, 4), listOf(4, 3, 2)).map { columns ->
            viewModel().apply {
                beginGesture()
                paintCells(columns.map { CellPosition(0, it) })
                endGesture()
            }.numbers(0)
        }
        assertEquals(listOf("1", "2", "3") + (4..10).map { "$it" }, oneEvent[0])
        assertEquals(oneEvent[0], oneEvent[1])

        // Erasing the block's first two seats: the start moves on, the others keep 6 … 10.
        for (columns in listOf(listOf(5, 6), listOf(6, 5))) {
            val vm = viewModel()
            vm.stroke(0, columns, CellType.GAP)
            assertEquals("eraser $columns", (6..10).map { "$it" }, vm.numbers(0))
        }
    }
}
