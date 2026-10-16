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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * "Starts at 0" means automatic (N20): Yorck has no seat 0, so the editor never
 * numbers one. The field keeps the digits as typed; only the parsed value counts.
 */
class BlockStartZeroTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Cinema Paris style: 1–4 | 12–15. */
    private val paris = SeatLayout.fromPatterns("SSSS|SSSS", firstSeatId = 1)
        .updateRow(0) { it.copy(blockStartNumbers = listOf(null, 12)) }

    private lateinit var repository: FakeCinexRepository

    private fun viewModel(layout: SeatLayout = paris): LayoutEditorViewModel {
        val hall = Hall(id = 1, cinemaId = 1, name = "Saal 1", layout = layout, screenWidth = 9f, screenDistance = 2f)
        repository = FakeCinexRepository(FakeStore(cinemas = listOf(Cinema(id = 1, name = "Kino")), halls = listOf(hall)))
        return LayoutEditorViewModel(1, repository, FakeReferenceImageStore()).apply { selectRow(0) }
    }

    private val LayoutEditorViewModel.state get() = uiState.value

    private fun LayoutEditorViewModel.numbers() = state.labels!!.seats.values
        .sortedBy { it.position.column }
        .map { it.label.seatNumberText }

    private fun LayoutEditorViewModel.starts() = state.draft!!.layout.rows[0].blockStartNumbers

    @Test
    fun zeroMakesTheBlockAutomatic() {
        val vm = viewModel()
        assertEquals(listOf("", "12"), vm.state.blockStartInputs)

        vm.setBlockStart(1, "0")
        assertEquals(emptyList<Int?>(), vm.starts())
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8"), vm.numbers())
        assertEquals(listOf("", "0"), vm.state.blockStartInputs) // as typed
        assertEquals(5, vm.state.row!!.blocks[1].firstNumber)

        vm.setBlockStart(1, "00")
        assertEquals(emptyList<Int?>(), vm.starts())
        assertEquals(listOf("", "00"), vm.state.blockStartInputs)
    }

    @Test
    fun leadingZerosCountOnlyWhileParsing() {
        val vm = viewModel()
        vm.setBlockStart(1, "05")
        assertEquals(listOf(null, 5), vm.starts())
        assertEquals(listOf("", "05"), vm.state.blockStartInputs)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8"), vm.numbers())

        vm.setBlockStart(1, "0012")
        assertEquals(listOf(null, 12), vm.starts())
        assertEquals(listOf("", "0012"), vm.state.blockStartInputs)
    }

    @Test
    fun backspacingTheFirstDigitKeepsTheZerosForTheNextOne() {
        val vm = viewModel()
        vm.setBlockStart(1, "10")
        assertEquals(listOf(null, 10), vm.starts())
        // The 1 deleted: "0" stays in the field (automatic meanwhile) ...
        vm.setBlockStart(1, "0")
        assertEquals(listOf("", "0"), vm.state.blockStartInputs)
        assertEquals(emptyList<Int?>(), vm.starts())
        // ... so a 2 typed in front of it gives 20.
        vm.setBlockStart(1, "20")
        assertEquals(listOf(null, 20), vm.starts())
        assertEquals(listOf("", "20"), vm.state.blockStartInputs)

        // Likewise "105" → "05" (5) → "205".
        vm.setBlockStart(1, "105")
        vm.setBlockStart(1, "05")
        assertEquals(listOf(null, 5), vm.starts())
        vm.setBlockStart(1, "205")
        assertEquals(listOf(null, 205), vm.starts())
        assertEquals(listOf("", "205"), vm.state.blockStartInputs)

        // Typing in one field is one undo step: back to 12.
        vm.undo()
        assertEquals(listOf(null, 12), vm.starts())
    }

    @Test
    fun zeroOnAnAutomaticBlockChangesNothing() {
        val vm = viewModel()
        vm.setBlockStart(0, "0")
        assertEquals(listOf(null, 12), vm.starts())
        assertEquals(listOf("0", "12"), vm.state.blockStartInputs)
        assertFalse(vm.state.canUndo)
        assertFalse(vm.state.hasUnsavedChanges)
        assertFalse("no seat 0", "0" in vm.numbers())
    }

    /** The model still reads a stored start of 0; the editor keeps it until that block's field is edited. */
    @Test
    fun aStoredZeroStartStaysUntilItsFieldIsEdited() {
        val stored = SeatLayout.fromPatterns("SSSS|SSSS", firstSeatId = 1)
            .updateRow(0) { it.copy(blockStartNumbers = listOf(null, 0)) }
        val vm = viewModel(stored)
        assertEquals(listOf(null, 0), vm.starts())
        assertEquals(listOf("", "0"), vm.state.blockStartInputs)
        assertEquals(listOf("1", "2", "3", "4", "0", "1", "2", "3"), vm.numbers())
        assertFalse(vm.state.hasUnsavedChanges)

        // Another block's field leaves it as it is.
        vm.setBlockStart(0, "2")
        assertEquals(listOf(2, 0), vm.starts())
        vm.selectRow(0) // deselect and select again: the fields are filled from the stored starts
        vm.selectRow(0)
        assertEquals(listOf("2", "0"), vm.state.blockStartInputs)

        // Typed into its own field, a 0 is automatic.
        vm.setBlockStart(1, "0")
        assertEquals(listOf(2), vm.starts())
        assertEquals(listOf("2", "3", "4", "5", "6", "7", "8", "9"), vm.numbers())
        assertTrue(vm.state.hasUnsavedChanges)

        // Saved without touching it, the stored 0 is kept.
        val untouched = viewModel(stored)
        untouched.setBlockStart(0, "2")
        untouched.save()
        assertTrue(untouched.state.isClosed)
        assertEquals(listOf(2, 0), repository.store.value.halls.single().layout!!.rows[0].blockStartNumbers)
    }
}
