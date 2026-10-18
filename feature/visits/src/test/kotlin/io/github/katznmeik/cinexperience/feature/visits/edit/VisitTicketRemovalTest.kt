package io.github.katznmeik.cinexperience.feature.visits.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.katznmeik.cinexperience.core.data.repository.TicketRepository
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * "Remove ticket" (spec §6.4) while its write is still running: the form can't be
 * closed, deleted or leave for the ticket meanwhile, and the removal runs to the
 * end even if the form goes away.
 */
class VisitTicketRemovalTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(SampleTickets.store())

    /** The fake's tickets, with a removal that waits for [gate]. */
    private class SlowRemoval(private val fake: FakeCinexRepository) : TicketRepository by fake {
        val gate = CompletableDeferred<Unit>()

        override suspend fun removeTicket(visitId: Long): Boolean {
            gate.await()
            return fake.removeTicket(visitId)
        }
    }

    private val tickets = SlowRemoval(repository)

    private fun viewModel() = VisitEditViewModel(
        VisitEditMode.Edit(SampleTickets.VISIT), repository, repository, FakeVisitPhotoStore(), SavedStateHandle(),
        ticketRepository = tickets, today = { LocalDate.of(2026, 10, 15) },
    )

    private fun VisitEditViewModel.startRemoval() {
        requestRemoveTicket()
        confirmRemoveTicket()
        assertTrue(uiState.value.isRemovingTicket)
    }

    @Test
    fun whileTheTicketIsRemovedTheFormStays() {
        val vm = viewModel()
        assertEquals(SampleTickets.VISIT, vm.ticketToShow())
        vm.startRemoval()

        vm.requestClose()
        vm.discard()
        vm.requestDelete()
        assertFalse(vm.uiState.value.isClosed)
        assertNull(vm.uiState.value.dialog)
        assertNull("no Show ticket meanwhile", vm.ticketToShow())

        tickets.gate.complete(Unit)
        assertTrue(repository.store.value.tickets.isEmpty())
        assertFalse(vm.uiState.value.isRemovingTicket)
        assertEquals(VisitEditViewModel.TICKET_REMOVED, vm.uiState.value.message)
        vm.requestClose() // nothing changed in the form: closes at once
        assertTrue(vm.uiState.value.isClosed)
    }

    @Test
    fun theRemovalRunsToTheEndWhenTheFormGoesAway() {
        val vm = viewModel()
        vm.startRemoval()
        vm.viewModelScope.cancel() // the form is gone, e.g. its screen was cleared
        tickets.gate.complete(Unit)
        assertTrue(repository.store.value.tickets.isEmpty())
    }
}
