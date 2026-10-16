package io.github.katznmeik.cinexperience.feature.cinemas

import io.github.katznmeik.cinexperience.core.model.TextLimits.MAX_LONG_TEXT_LENGTH
import io.github.katznmeik.cinexperience.core.model.TextLimits.MAX_NAME_LENGTH
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditDialog
import io.github.katznmeik.cinexperience.feature.cinemas.manage.ManageCinemasViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The cinema and hall dialogs stop at the repositories' text limits (as the visit
 * form does), so a long paste is cut there instead of making the save fail.
 */
class EditDialogTextLimitsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(SampleData.store())

    /** U+1F3AC CLAPPER BOARD: one code point, two chars. */
    private val emoji = "\uD83C\uDFAC"

    private fun TestScope.viewModel(): ManageCinemasViewModel {
        val vm = ManageCinemasViewModel(repository, repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private val ManageCinemasViewModel.form get() = uiState.value.dialog as EditDialog.CinemaForm

    @Test
    fun cinemaFieldsStopAtTheirLimits() = runTest {
        val vm = viewModel()
        vm.addCinema()
        vm.onNameChange("N".repeat(MAX_NAME_LENGTH + 5))
        vm.onAddressChange("A".repeat(MAX_NAME_LENGTH + 5))
        vm.onNotesChange("L".repeat(MAX_LONG_TEXT_LENGTH + 5))
        assertEquals("N".repeat(MAX_NAME_LENGTH), vm.form.name)
        assertEquals("A".repeat(MAX_NAME_LENGTH), vm.form.address)
        assertEquals("L".repeat(MAX_LONG_TEXT_LENGTH), vm.form.notes)

        vm.confirmDialog()
        assertNull(vm.uiState.value.dialog)
        assertNull(vm.uiState.value.message)
        val saved = repository.store.value.cinemas.single { it.name == "N".repeat(MAX_NAME_LENGTH) }
        assertEquals("A".repeat(MAX_NAME_LENGTH), saved.address)
    }

    @Test
    fun theLimitCountsCodePointsAndNeverSplitsOne() = runTest {
        val vm = viewModel()
        vm.addCinema()
        vm.onNameChange(emoji.repeat(MAX_NAME_LENGTH + 1))
        assertEquals(emoji.repeat(MAX_NAME_LENGTH), vm.form.name)
    }

    @Test
    fun savingTheDialogShortensATextStoredBeforeTheLimits() = runTest {
        val long = SampleData.store().let { store ->
            store.copy(cinemas = store.cinemas.mapIndexed { i, c -> if (i == 0) c.copy(name = "N".repeat(MAX_NAME_LENGTH + 5)) else c })
        }
        repository.store.value = long
        val vm = viewModel()
        val cinema = vm.uiState.value.cinemas.single { it.cinema.name.length > MAX_NAME_LENGTH }
        vm.editCinema(cinema)
        assertEquals("N".repeat(MAX_NAME_LENGTH), vm.form.name)
        vm.confirmDialog()
        assertNull(vm.uiState.value.message)
        assertEquals("N".repeat(MAX_NAME_LENGTH), repository.store.value.cinemas.single { it.id == cinema.cinema.id }.name)
    }

    @Test
    fun hallNamesStopAtTheLimit() = runTest {
        val vm = viewModel()
        val cinema = vm.uiState.value.cinemas.first()
        vm.addHall(cinema.cinema.id)
        vm.onNameChange("H".repeat(MAX_NAME_LENGTH + 1))
        assertEquals("H".repeat(MAX_NAME_LENGTH), (vm.uiState.value.dialog as EditDialog.HallName).name)
        vm.confirmDialog()
        assertNull(vm.uiState.value.message)
        assertEquals(1, repository.store.value.halls.count { it.name == "H".repeat(MAX_NAME_LENGTH) })
    }
}
