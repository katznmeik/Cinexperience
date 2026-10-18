package io.github.katznmeik.cinexperience.feature.visits.edit

import androidx.lifecycle.SavedStateHandle
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Editing a visit without touching its Start time field keeps the start time it has
 * (e.g. from a ticket import), also after process death, and doesn't touch its
 * ticket. (The field itself: VisitStartTimeFieldTest.)
 */
class VisitEditStartTimeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(SampleData.storeWithSplitHall())
    private val photos = FakeVisitPhotoStore()
    private val handle = SavedStateHandle()
    private val evening = LocalTime.of(20, 15)

    /** A visit with a start time and an (invented) ticket, as the ticket import saves it. */
    private val visit = Visit(
        id = 901,
        hallId = SampleData.DELPHI_LUX_SAAL_1,
        date = LocalDate.of(2026, 10, 24),
        filmTitle = "The Quiet Projectionist",
        startTime = evening,
    )
    private val ticket = Ticket(901, BookingNumber("TEST123"), listOf(TicketSeat("7", "10")))

    private fun viewModel(mode: VisitEditMode = VisitEditMode.Edit(visit.id)) =
        VisitEditViewModel(mode, repository, repository, photos, handle) { LocalDate.of(2026, 10, 17) }

    private fun stored(): Visit = repository.store.value.visits.single { it.id == visit.id }

    private fun storeVisit() {
        repository.store.value = repository.store.value.copy(
            visits = repository.store.value.visits + visit,
            tickets = repository.store.value.tickets + ticket,
        )
    }

    @Test
    fun editingAVisitKeepsItsStartTimeAndTicket() {
        storeVisit()
        val vm = viewModel()
        vm.setFilmTitle("The Quiet Projectionist (OmU)")
        vm.save()

        assertEquals("The Quiet Projectionist (OmU)", stored().filmTitle)
        assertEquals(evening, stored().startTime)
        assertEquals(listOf(ticket), repository.store.value.tickets)
    }

    @Test
    fun aRestoredEditKeepsTheStartTime() {
        storeVisit()
        viewModel().setNote("Row 7 was fine")
        // Process death: a new ViewModel on the same saved state.
        val restored = viewModel()
        assertEquals("Row 7 was fine", restored.uiState.value.form.note)
        restored.save()

        assertEquals("Row 7 was fine", stored().note)
        assertEquals(evening, stored().startTime)
    }

    @Test
    fun aNewVisitHasNoStartTime() {
        val vm = viewModel(VisitEditMode.Add(SampleData.DELPHI_LUX_SAAL_1))
        vm.setFilmTitle("Anora")
        vm.save()
        assertNull(repository.store.value.visits.single { it.filmTitle == "Anora" }.startTime)
    }
}
