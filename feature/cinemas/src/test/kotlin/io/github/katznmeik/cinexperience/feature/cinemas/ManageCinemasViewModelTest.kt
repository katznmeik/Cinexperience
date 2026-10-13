package io.github.katznmeik.cinexperience.feature.cinemas

import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditDialog
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditMessages
import io.github.katznmeik.cinexperience.feature.cinemas.manage.ManageCinemasViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ManageCinemasViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(SampleData.store())

    private fun TestScope.viewModel(): ManageCinemasViewModel {
        val vm = ManageCinemasViewModel(repository, repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun countsCinemasHallsAndDrawnLayouts() = runTest {
        val state = viewModel().uiState.value
        assertEquals(13, state.cinemas.size)
        assertEquals(36, state.hallCount)
        assertEquals(4, state.layoutsDrawn)
    }

    @Test
    fun addCinemaNeedsANameAndAddressIsOptional() = runTest {
        val vm = viewModel()
        vm.addCinema()
        assertTrue((vm.uiState.value.dialog as EditDialog.CinemaForm).isNew)
        vm.onAddressChange("Somewhere 1")
        vm.confirmDialog()
        assertEquals(EditMessages.NAME_REQUIRED, (vm.uiState.value.dialog as EditDialog.CinemaForm).nameError)
        assertEquals(13, vm.uiState.value.cinemas.size)

        vm.onNameChange("  Sommerkino  ")
        vm.onAddressChange("")
        vm.confirmDialog()
        assertNull(vm.uiState.value.dialog)
        val added = vm.uiState.value.cinemas.single { it.cinema.name == "Sommerkino" }
        assertNull(added.cinema.address)
        assertEquals(false, added.cinema.isPreloaded)
    }

    @Test
    fun duplicateCinemaNamesAreAllowed() = runTest {
        val vm = viewModel()
        vm.addCinema()
        vm.onNameChange("Odeon")
        vm.confirmDialog()
        assertEquals(2, vm.uiState.value.cinemas.count { it.cinema.name == "Odeon" })
    }

    @Test
    fun deleteRules() = runTest {
        val vm = viewModel()
        val preloaded = vm.uiState.value.cinemas.first { it.cinema.id == SampleData.PASSAGE }
        vm.deleteCinema(preloaded)
        assertNull(vm.uiState.value.dialog)
        assertEquals(EditMessages.PRELOADED_CINEMA, vm.uiState.value.message)

        val id = repository.createCinema("Mine")
        vm.addHall(id)
        vm.onNameChange("Saal 1")
        vm.confirmDialog()
        val mine = vm.uiState.value.cinemas.single { it.cinema.id == id }
        assertEquals(1, mine.halls.size)

        vm.deleteHall(mine.halls.single())
        assertEquals(EditDialog.ConfirmDeleteHall(mine.halls.single().hall.id, "Saal 1", 0), vm.uiState.value.dialog)
        vm.dismissDialog()
        assertNull(vm.uiState.value.dialog)

        vm.deleteCinema(mine)
        assertEquals(EditDialog.ConfirmDeleteCinema(id, "Mine", 1, 0), vm.uiState.value.dialog)
        vm.confirmDialog()
        assertTrue(vm.uiState.value.cinemas.none { it.cinema.id == id })
        assertEquals(13, vm.uiState.value.cinemas.size)
    }

    @Test
    fun deleteFailureBecomesMessage() = runTest {
        val vm = viewModel()
        val id = repository.createCinema("Mine")
        vm.deleteCinema(vm.uiState.value.cinemas.single { it.cinema.id == id })
        repository.failure = IllegalStateException("io")
        vm.confirmDialog()
        assertEquals(EditMessages.DELETE_FAILED, vm.uiState.value.message)
        assertTrue(vm.uiState.value.cinemas.any { it.cinema.id == id })
    }
}
