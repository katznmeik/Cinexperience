package io.github.katznmeik.cinexperience.feature.visits.edit

import androidx.lifecycle.SavedStateHandle
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** The visit form's optional Start time field (spec §6.4): set, clear, saved, kept across process death. */
class VisitStartTimeFieldTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(SampleTickets.store(tickets = emptyList()))
    private val photos = FakeVisitPhotoStore()
    private val handle = SavedStateHandle()

    private fun viewModel(mode: VisitEditMode = VisitEditMode.Edit(SampleTickets.VISIT)) =
        VisitEditViewModel(mode, repository, repository, photos, handle, today = { LocalDate.of(2026, 10, 15) }, ticketRepository = repository)

    private fun storedStartTime(id: Long = SampleTickets.VISIT): LocalTime? = repository.store.value.visits.single { it.id == id }.startTime

    @Test
    fun settingTheStartTimeIsAChangeAndIsSaved() {
        val vm = viewModel(VisitEditMode.Add(SampleTickets.HALL))
        assertNull(vm.uiState.value.form.startTime)
        vm.setStartTime(LocalTime.of(17, 45))
        assertEquals(LocalTime.of(17, 45), vm.uiState.value.form.startTime)
        assertTrue(vm.uiState.value.hasUnsavedChanges)
        vm.setFilmTitle("Anora")
        vm.save()
        val saved = repository.store.value.visits.single { it.filmTitle == "Anora" }
        assertEquals(LocalTime.of(17, 45), saved.startTime)
    }

    @Test
    fun theTimeIsToTheMinute() {
        val vm = viewModel(VisitEditMode.Add(SampleTickets.HALL))
        vm.setStartTime(LocalTime.of(17, 45, 30, 500))
        assertEquals(LocalTime.of(17, 45), vm.uiState.value.form.startTime)
    }

    @Test
    fun clearingTheStartTimeSavesNone() {
        val vm = viewModel()
        assertEquals(SampleTickets.START, vm.uiState.value.form.startTime)
        vm.setStartTime(null)
        assertNull(vm.uiState.value.form.startTime)
        assertTrue(vm.uiState.value.hasUnsavedChanges)
        vm.save()
        assertNull(storedStartTime())
    }

    @Test
    fun settingItBackIsNoChange() {
        val vm = viewModel()
        vm.setStartTime(LocalTime.of(18, 0))
        vm.setStartTime(SampleTickets.START)
        assertFalse(vm.uiState.value.hasUnsavedChanges)
    }

    @Test
    fun aChangedOrClearedStartTimeSurvivesProcessDeath() {
        viewModel().setStartTime(LocalTime.of(22, 30))
        // Process death: a new ViewModel on the same saved state.
        val restored = viewModel()
        assertEquals(LocalTime.of(22, 30), restored.uiState.value.form.startTime)
        assertTrue(restored.uiState.value.hasUnsavedChanges)

        restored.setStartTime(null)
        val again = viewModel()
        assertNull(again.uiState.value.form.startTime)
        again.save()
        assertNull(storedStartTime())
    }
}
