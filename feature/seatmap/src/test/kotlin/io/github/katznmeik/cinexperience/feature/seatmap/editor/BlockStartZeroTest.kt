package io.github.katznmeik.cinexperience.feature.seatmap.editor

import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.Hall
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

/** "Starts at 0" means automatic (N20): Yorck has no seat 0, so the editor never numbers one. */
class BlockStartZeroTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Cinema Paris style: 1–4 | 12–15. */
    private val stored = SeatLayout.fromPatterns("SSSS|SSSS", firstSeatId = 1)
        .updateRow(0) { it.copy(blockStartNumbers = listOf(null, 12)) }
    private val hall = Hall(id = 1, cinemaId = 1, name = "Saal 1", layout = stored, screenWidth = 9f, screenDistance = 2f)

    private val repository = FakeCinexRepository(FakeStore(cinemas = listOf(Cinema(id = 1, name = "Kino")), halls = listOf(hall)))

    private fun viewModel() = LayoutEditorViewModel(1, repository, FakeReferenceImageStore()).apply { selectRow(0) }

    private val LayoutEditorViewModel.state get() = uiState.value

    private fun LayoutEditorViewModel.numbers() = state.labels!!.seats.values
        .sortedBy { it.position.column }
        .map { it.label.seatNumberText }

    private fun LayoutEditorViewModel.starts() = state.draft!!.layout.rows[0].blockStartNumbers

    @Test
    fun zeroMakesTheBlockAutomaticAndClearsTheField() {
        val vm = viewModel()
        assertEquals(listOf("", "12"), vm.state.blockStartInputs)

        vm.setBlockStart(1, "0")
        assertEquals(emptyList<Int?>(), vm.starts())
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8"), vm.numbers())
        // The field is empty, so it shows the automatic first number as its placeholder.
        assertEquals(listOf("", ""), vm.state.blockStartInputs)
        assertEquals(5, vm.state.row!!.blocks[1].firstNumber)

        vm.setBlockStart(1, "000")
        assertEquals(emptyList<Int?>(), vm.starts())
        assertEquals(listOf("", ""), vm.state.blockStartInputs)
    }

    @Test
    fun leadingZerosAreDropped() {
        val vm = viewModel()
        vm.setBlockStart(1, "05")
        assertEquals(listOf(null, 5), vm.starts())
        assertEquals(listOf("", "5"), vm.state.blockStartInputs)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8"), vm.numbers())

        // Zeros don't count towards the four digits.
        vm.setBlockStart(1, "00012")
        assertEquals(listOf(null, 12), vm.starts())
        assertEquals(listOf("", "12"), vm.state.blockStartInputs)

        // A zero after other digits is part of the number.
        vm.setBlockStart(1, "10")
        assertEquals(listOf(null, 10), vm.starts())
    }

    @Test
    fun zeroOnAnAutomaticBlockChangesNothing() {
        val vm = viewModel()
        vm.setBlockStart(0, "0")
        assertEquals(listOf(null, 12), vm.starts())
        assertEquals(listOf("", "12"), vm.state.blockStartInputs)
        assertFalse(vm.state.canUndo)
        assertFalse(vm.state.hasUnsavedChanges)
        assertFalse("no seat 0", "0" in vm.numbers())
    }
}
