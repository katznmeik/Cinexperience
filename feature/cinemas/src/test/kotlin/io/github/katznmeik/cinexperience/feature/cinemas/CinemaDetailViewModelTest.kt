package io.github.katznmeik.cinexperience.feature.cinemas

import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailContent
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailViewModel
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditDialog
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditMessages
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class CinemaDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(SampleData.store())

    private fun TestScope.viewModel(cinemaId: Long = SampleData.DELPHI_LUX): CinemaDetailViewModel {
        val vm = CinemaDetailViewModel(cinemaId, repository, repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private val CinemaDetailViewModel.detail: CinemaDetail
        get() = (uiState.value.content as CinemaDetailContent.Loaded).detail

    private fun hallNames(cinemaId: Long) =
        repository.store.value.halls.filter { it.cinemaId == cinemaId }.map { it.name }

    @Test
    fun loadsCinemaWithHallsAndScores() = runTest {
        val vm = viewModel()
        val detail = vm.detail
        assertEquals("delphi LUX", detail.cinema.name)
        assertEquals(7, detail.halls.size)
        assertNotNull(detail.scores.overall)
        assertNotNull(detail.halls.first { it.hall.id == SampleData.DELPHI_LUX_SAAL_1 }.favouriteSeat)
    }

    @Test
    fun unknownCinemaIsNotFound() = runTest {
        assertEquals(CinemaDetailContent.NotFound, viewModel(cinemaId = 999).uiState.value.content)
    }

    @Test
    fun blankHallNameIsRejectedInline() = runTest {
        val vm = viewModel()
        vm.addHall()
        vm.onNameChange("   ")
        vm.confirmDialog()

        val dialog = vm.uiState.value.dialog as EditDialog.HallName
        assertEquals(EditMessages.NAME_REQUIRED, dialog.nameError)
        assertEquals(7, hallNames(SampleData.DELPHI_LUX).size)

        vm.onNameChange("S")
        assertNull((vm.uiState.value.dialog as EditDialog.HallName).nameError)
    }

    @Test
    fun addHallAllowsDuplicateNames() = runTest {
        val vm = viewModel()
        vm.addHall()
        vm.onNameChange("Saal 1")
        vm.confirmDialog()

        assertNull(vm.uiState.value.dialog)
        assertEquals(2, hallNames(SampleData.DELPHI_LUX).count { it == "Saal 1" })
        assertEquals(8, vm.detail.halls.size)
    }

    @Test
    fun renamePreloadedHall() = runTest {
        val vm = viewModel()
        val hall = vm.detail.halls.first { it.hall.id == SampleData.DELPHI_LUX_SAAL_6 }
        vm.renameHall(hall)
        assertEquals("Saal 6", (vm.uiState.value.dialog as EditDialog.HallName).name)
        vm.onNameChange("Saal 6 (Lounge)")
        vm.confirmDialog()
        assertTrue("Saal 6 (Lounge)" in hallNames(SampleData.DELPHI_LUX))
    }

    @Test
    fun preloadedHallCannotBeDeleted() = runTest {
        val vm = viewModel()
        vm.deleteHall(vm.detail.halls.first())

        assertNull(vm.uiState.value.dialog)
        assertEquals(EditMessages.PRELOADED_HALL, vm.uiState.value.message)
        assertEquals(7, hallNames(SampleData.DELPHI_LUX).size)
        vm.messageShown()
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun userAddedHallIsDeletedAfterConfirmationShowingItsVisits() = runTest {
        val hallId = repository.createHall(SampleData.DELPHI_LUX, "Open air")
        repository.saveVisit(Visit(hallId = hallId, date = LocalDate.of(2026, 7, 1)))
        val vm = viewModel()

        vm.deleteHall(vm.detail.halls.first { it.hall.id == hallId })
        val confirm = vm.uiState.value.dialog as EditDialog.ConfirmDeleteHall
        assertEquals(1, confirm.visitCount)
        assertTrue("Open air" in hallNames(SampleData.DELPHI_LUX))

        vm.confirmDialog()
        assertFalse("Open air" in hallNames(SampleData.DELPHI_LUX))
        assertEquals(EditMessages.deleted("Open air"), vm.uiState.value.message)
    }

    @Test
    fun preloadedCinemaCanBeEditedButNotDeleted() = runTest {
        val vm = viewModel()
        vm.editCinema()
        val form = vm.uiState.value.dialog as EditDialog.CinemaForm
        assertFalse(form.canDelete)
        assertEquals("Kantstraße 10, 10623 Berlin", form.address)

        vm.onNameChange("Delphi Lux")
        vm.onNotesChange("Best popcorn in town")
        vm.confirmDialog()
        assertEquals("Delphi Lux", vm.detail.cinema.name)
        assertEquals("Best popcorn in town", vm.detail.cinema.notes)

        vm.deleteCinema()
        assertEquals(EditMessages.PRELOADED_CINEMA, vm.uiState.value.message)
        assertNotNull(repository.store.value.cinemas.firstOrNull { it.id == SampleData.DELPHI_LUX })
    }

    @Test
    fun blankCinemaNameIsRejected() = runTest {
        val vm = viewModel()
        vm.editCinema()
        vm.onNameChange("")
        vm.confirmDialog()
        assertEquals(EditMessages.NAME_REQUIRED, (vm.uiState.value.dialog as EditDialog.CinemaForm).nameError)
        assertEquals("delphi LUX", vm.detail.cinema.name)
    }

    @Test
    fun userAddedCinemaIsDeletedWithHallsAndVisitsAndScreenCloses() = runTest {
        val cinemaId = repository.createCinema("Sommerkino")
        val hallId = repository.createHall(cinemaId, "Saal 1")
        repository.saveVisit(Visit(hallId = hallId, date = LocalDate.of(2026, 7, 1)))
        val vm = viewModel(cinemaId)

        vm.editCinema()
        assertTrue((vm.uiState.value.dialog as EditDialog.CinemaForm).canDelete)
        // Counts are taken when "Delete cinema" is pressed, not when the form opened.
        repository.saveVisit(Visit(hallId = hallId, date = LocalDate.of(2026, 8, 1)))
        vm.deleteFromForm()
        assertEquals(EditDialog.ConfirmDeleteCinema(cinemaId, "Sommerkino", 1, 2), vm.uiState.value.dialog)

        vm.confirmDialog()
        assertEquals(EditMessages.deleted("Sommerkino"), vm.uiState.value.deletedMessage)
        // The screen closes with the last loaded content instead of "not found",
        // and the message goes to the screen below rather than this one's snackbar.
        assertTrue(vm.uiState.value.content is CinemaDetailContent.Loaded)
        assertNull(vm.uiState.value.message)
        val store = repository.store.value
        assertTrue(store.cinemas.none { it.id == cinemaId })
        assertTrue(store.halls.none { it.id == hallId })
        assertTrue(store.visits.none { it.hallId == hallId })
    }

    @Test
    fun failedCinemaDeleteKeepsTheScreenOpen() = runTest {
        val cinemaId = repository.createCinema("Sommerkino")
        val vm = viewModel(cinemaId)
        vm.editCinema()
        vm.deleteFromForm()
        repository.failure = IllegalStateException("io")
        vm.confirmDialog()

        assertNull(vm.uiState.value.deletedMessage)
        assertEquals(EditMessages.DELETE_FAILED, vm.uiState.value.message)
        assertTrue(vm.uiState.value.content is CinemaDetailContent.Loaded)
    }

    @Test
    fun repositoryFailureBecomesMessageInsteadOfCrash() = runTest {
        val vm = viewModel()
        repository.failure = IllegalStateException("disk full")
        vm.addHall()
        vm.onNameChange("Saal 8")
        vm.confirmDialog()

        assertNull(vm.uiState.value.dialog)
        assertEquals(EditMessages.SAVE_FAILED, vm.uiState.value.message)
        assertEquals(7, hallNames(SampleData.DELPHI_LUX).size)
    }

    @Test
    fun loadFailureIsShown() = runTest {
        repository.observeFailure = IllegalStateException("db")
        assertEquals(
            CinemaDetailContent.Error(CinemaDetailViewModel.LOAD_FAILED),
            viewModel().uiState.value.content,
        )
    }
}
