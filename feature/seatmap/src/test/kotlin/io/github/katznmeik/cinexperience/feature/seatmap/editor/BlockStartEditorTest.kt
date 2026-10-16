package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.model.CellType
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.seatmapui.seatNumberText
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReferenceImageStore
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Block starts follow their seats in the editor (I5, spec §4.3 / §6.5): the
 * "Starts at" fields stay in step with the re-keyed blocks, and Undo restores them.
 */
class BlockStartEditorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Row 1: Kant Kino style, right to left from 3 (10 … 3). Row 2: Cinema Paris style, 1–4 | 12–15. */
    private val stored = SeatLayout.fromPatterns("SSSSSSSS.", "SSSS|SSSS", firstSeatId = 1)
        .updateRow(0) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT, blockStartNumbers = listOf(3)) }
        .updateRow(1) { it.copy(blockStartNumbers = listOf(null, 12)) }
    private val hall = Hall(id = 1, cinemaId = 1, name = "Saal 1", layout = stored, screenWidth = 9f, screenDistance = 2f)

    private val repository = FakeCinexRepository(FakeStore(cinemas = listOf(Cinema(id = 1, name = "Kino")), halls = listOf(hall)))

    private fun viewModel() = LayoutEditorViewModel(1, repository, FakeReferenceImageStore())

    private val LayoutEditorViewModel.state get() = uiState.value

    private fun LayoutEditorViewModel.numbers(row: Int) = state.labels!!.seats.values
        .filter { it.position.row == row }
        .sortedBy { it.position.column }
        .map { it.label.seatNumberText }

    private fun LayoutEditorViewModel.starts(row: Int) = state.draft!!.layout.rows[row].blockStartNumbers

    @Test
    fun paintingAnAisleKeepsTheNumbersAndTheStartFieldsFollowTheBlocks() {
        val vm = viewModel()
        assertEquals(listOf("10", "9", "8", "7", "6", "5", "4", "3"), vm.numbers(0))
        assertEquals(listOf("1", "2", "3", "4", "12", "13", "14", "15"), vm.numbers(1))

        vm.selectRow(0)
        assertEquals(listOf("3"), vm.state.blockStartInputs)
        vm.selectTool(CellType.AISLE)
        vm.tapCell(0, 5)
        assertEquals(listOf("9", "8", "7", "6", "5", "4", "3"), vm.numbers(0))
        assertEquals(listOf(null, 3), vm.starts(0))
        // The fields follow the blocks: the right block (first in numbering order) holds the 3.
        assertEquals(listOf("", "3"), vm.state.blockStartInputs)
        assertEquals(listOf(1, 0), vm.state.row!!.blocksInNumberingOrder.map { it.index })
        assertEquals(listOf("3–4", "5–9"), vm.state.row!!.blocksInNumberingOrder.map { it.rangeText })
        assertEquals(listOf(null, 3), vm.state.row!!.blocks.map { it.startNumber })

        // Typing into a field still addresses the block it shows.
        vm.setBlockStart(0, "20")
        assertEquals(listOf("20", "3"), vm.state.blockStartInputs)
        assertEquals(listOf("24", "23", "22", "21", "20", "4", "3"), vm.numbers(0))

        // Paris: an aisle in the left block leaves the right block at 12.
        vm.selectRow(1)
        vm.tapCell(1, 2)
        assertEquals(listOf("1", "2", "3", "12", "13", "14", "15"), vm.numbers(1))
        assertEquals(listOf(null, null, 12), vm.starts(1))
        assertEquals(listOf("", "", "12"), vm.state.blockStartInputs)
        assertEquals(listOf("1–2", "3", "12–15"), vm.state.row!!.blocks.map { it.rangeText })

        // Undo restores the layout, the starts and the fields, step by step.
        vm.undo()
        assertEquals(listOf("1", "2", "3", "4", "12", "13", "14", "15"), vm.numbers(1))
        assertEquals(listOf("", "12"), vm.state.blockStartInputs)
        vm.selectRow(0)
        vm.undo() // the typed 20
        assertEquals(listOf("", "3"), vm.state.blockStartInputs)
        vm.undo() // the aisle
        assertEquals(listOf("10", "9", "8", "7", "6", "5", "4", "3"), vm.numbers(0))
        assertEquals(listOf(3), vm.starts(0))
        assertEquals(listOf("3"), vm.state.blockStartInputs)
        assertFalse(vm.state.hasUnsavedChanges)
        assertEquals(stored.rows, vm.state.draft!!.layout.rows)
    }

    @Test
    fun paintingTheFirstSeatAwayAndBackKeepsItsStart() {
        val vm = viewModel()
        vm.selectRow(0)
        vm.selectTool(CellType.GAP)
        vm.tapCell(0, 7) // the seat numbered 3, the start's seat: the others keep their numbers
        assertEquals(listOf("10", "9", "8", "7", "6", "5", "4"), vm.numbers(0))
        assertEquals(listOf(4), vm.starts(0))
        assertEquals(listOf("4"), vm.state.blockStartInputs)
        assertTrue(vm.state.hasUnsavedChanges)

        vm.selectTool(CellType.SEAT)
        vm.tapCell(0, 7)
        assertEquals(listOf("10", "9", "8", "7", "6", "5", "4", "3"), vm.numbers(0))
        assertEquals(listOf("3"), vm.state.blockStartInputs)
        assertFalse(vm.state.hasUnsavedChanges)
    }

    @Test
    fun aDraftReKeysThroughPaintAndColumnChangesAndUndoHistoryRestoresIt() {
        val draft = LayoutDraft.start(hall)
        val history = UndoHistory<LayoutDraft>()
        fun LayoutDraft.texts(row: Int) = layout.rows[row].seatIds.map { layout.labels().labelOf(it)!!.seat }

        history.record(draft)
        val split = draft.paint(1, 2, CellType.AISLE) // SS|S|SSSS: the 12 stays with its seat in the right block
        assertEquals(listOf(null, null, 12), split.layout.rows[1].blockStartNumbers)
        assertEquals(listOf("1", "2", "3", "12", "13", "14", "15"), split.texts(1))

        history.record(split)
        val narrower = split.withColumnCount(8) // drops the last column: row 0's gap, a seat of row 1's right block
        assertEquals(listOf(null, null, 12), narrower.layout.rows[1].blockStartNumbers)
        assertEquals(listOf("1", "2", "3", "12", "13", "14"), narrower.texts(1))
        assertEquals(listOf(3), narrower.layout.rows[0].blockStartNumbers)
        assertEquals(listOf("10", "9", "8", "7", "6", "5", "4", "3"), narrower.texts(0))

        history.record(narrower)
        val wider = narrower.withColumnCount(10) // new seat columns copy the last column: they extend the last block
        assertEquals(listOf(null, null, 12), wider.layout.rows[1].blockStartNumbers)
        assertEquals(listOf("1", "2", "3", "12", "13", "14", "15", "16"), wider.texts(1))
        // Row 0 runs right to left: its new seats come first and count back from the 3, the others keep theirs.
        assertEquals(listOf(1), wider.layout.rows[0].blockStartNumbers)
        assertEquals(listOf("10", "9", "8", "7", "6", "5", "4", "3", "2", "1"), wider.texts(0))

        val back = history.undo(wider)!!.let { history.undo(it)!! }.let { history.undo(it)!! }
        assertEquals(draft.layout.rows, back.layout.rows)
        assertTrue(back.hasSameContentAs(draft))
    }
}
