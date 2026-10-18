package io.github.katznmeik.cinexperience.feature.visits.edit

import androidx.lifecycle.SavedStateHandle
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeStore
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

/**
 * The visit form's Ticket section (spec §6.4): it shows the stored ticket, Save never
 * touches the ticket or its QR code, and "Remove ticket" removes it after a confirmation.
 */
class VisitTicketSectionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(SampleTickets.store())
    private val photos = FakeVisitPhotoStore()
    private val handle = SavedStateHandle()

    private fun viewModel(
        mode: VisitEditMode = VisitEditMode.Edit(SampleTickets.VISIT),
        store: FakeCinexRepository? = repository,
        savedState: SavedStateHandle = handle,
    ) = VisitEditViewModel(mode, repository, repository, photos, savedState, today = { LocalDate.of(2026, 10, 15) }, ticketRepository = store)

    private val stored: FakeStore get() = repository.store.value

    @Test
    fun aVisitWithATicketShowsIt() {
        val ticket = viewModel().uiState.value.ticket
        assertEquals(SampleTickets.ticket(), ticket)
        assertEquals(SampleTickets.VISIT, ticket?.visitId)
    }

    @Test
    fun visitsWithoutTicketShowNoSection() {
        assertNull(viewModel(VisitEditMode.Add(SampleTickets.HALL), savedState = SavedStateHandle()).uiState.value.ticket)
        assertNull("no ticket repository", viewModel(store = null, savedState = SavedStateHandle()).uiState.value.ticket)
        val other = stored.visits.first { it.id != SampleTickets.VISIT }.id
        assertNull(viewModel(VisitEditMode.Edit(other), savedState = SavedStateHandle()).uiState.value.ticket)
    }

    @Test
    fun savingTheFormKeepsTheTicketAndItsQrCode() {
        val vm = viewModel()
        vm.setNote("Row 7 again")
        vm.setStartTime(LocalTime.of(20, 30))
        vm.setFilmTitle("The Quiet Projectionist (OmU)")
        vm.save()
        assertTrue(vm.uiState.value.isClosed)
        assertEquals(listOf(SampleTickets.ticket()), stored.tickets)
        assertEquals(SampleTickets.qrModules(), stored.tickets.single().qrModules)
        val visit = stored.visits.single { it.id == SampleTickets.VISIT }
        assertEquals("Row 7 again", visit.note)
        assertEquals(LocalTime.of(20, 30), visit.startTime)
    }

    @Test
    fun aRestoredFormStillShowsTheTicketAndSavingKeepsIt() {
        viewModel().setNote("Halfway through")
        val restored = viewModel()
        assertEquals(SampleTickets.ticket(), restored.uiState.value.ticket)
        restored.save()
        assertEquals(listOf(SampleTickets.ticket()), stored.tickets)
    }

    @Test
    fun removeTicketAsksFirstThenRemovesItAtOnce() {
        val vm = viewModel()
        vm.setNote("Unsaved note")
        vm.requestRemoveTicket()
        assertEquals(VisitEditDialog.ConfirmRemoveTicket, vm.uiState.value.dialog)
        assertEquals("nothing removed before the confirmation", 1, stored.tickets.size)

        vm.confirmRemoveTicket()
        val state = vm.uiState.value
        assertNull(state.dialog)
        assertNull(state.ticket)
        assertEquals(VisitEditViewModel.TICKET_REMOVED, state.message)
        assertTrue(stored.tickets.isEmpty())
        // The visit itself and the form's unsaved edits stay.
        assertEquals(SampleTickets.FILM, stored.visits.single { it.id == SampleTickets.VISIT }.filmTitle)
        assertEquals("Unsaved note", state.form.note)
        assertTrue(state.hasUnsavedChanges)
        assertFalse(state.isClosed)
    }

    @Test
    fun cancellingTheConfirmationKeepsTheTicket() {
        val vm = viewModel()
        vm.requestRemoveTicket()
        vm.dismissDialog()
        assertEquals(SampleTickets.ticket(), vm.uiState.value.ticket)
        assertEquals(1, stored.tickets.size)
    }

    @Test
    fun aFailedRemovalIsAMessageAndTheTicketStays() {
        val vm = viewModel()
        vm.requestRemoveTicket()
        repository.failure = IllegalStateException("disk full")
        vm.confirmRemoveTicket()
        assertEquals(VisitEditViewModel.REMOVE_TICKET_FAILED, vm.uiState.value.message)
        assertEquals(SampleTickets.ticket(), vm.uiState.value.ticket)
        assertFalse(vm.uiState.value.isRemovingTicket)
    }

    @Test
    fun aTicketRemovedElsewhereLeavesTheForm() {
        val vm = viewModel()
        repository.store.value = stored.copy(tickets = emptyList())
        assertNull(vm.uiState.value.ticket)
        vm.requestRemoveTicket()
        assertNull("nothing to remove", vm.uiState.value.dialog)
    }
}
