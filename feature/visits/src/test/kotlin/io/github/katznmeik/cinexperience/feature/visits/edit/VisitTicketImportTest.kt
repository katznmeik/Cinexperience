package io.github.katznmeik.cinexperience.feature.visits.edit

import androidx.lifecycle.SavedStateHandle
import io.github.katznmeik.cinexperience.core.data.repository.SaveTicketResult
import io.github.katznmeik.cinexperience.core.data.repository.TicketRepository
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.TicketDraft
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.VisitPrefillArgs
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Add visit with an imported ticket (spec §6.10): the draft is shown and kept across
 * process death, "Remove ticket" drops it, Save is the only `saveVisitWithTicket`
 * call (with the right seats and QR code), and a booking number imported meanwhile
 * opens that visit instead. Edit saves never touch tickets. Data is invented.
 */
class VisitTicketImportTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = Clock.fixed(SampleProgram.FETCHED_AT, ZoneOffset.UTC)
    private val saturday = LocalDate.of(2026, 10, 17)
    private val seats = listOf(TicketSeat("7", "10"), TicketSeat("7", "11"))
    private val qr = SampleTickets.qrModules()
    private val draft = TicketDraft(BookingNumber("TEST123"), seats, qr)
    private val notes = listOf("Not on the ticket: film title")
    private val photos = FakeVisitPhotoStore()
    private val handle = SavedStateHandle()

    /** delphi LUX Saal 4 drawn ([SampleTickets.saal4Layout]), no visits and no tickets. */
    private var repository = FakeCinexRepository(SampleTickets.store().copy(visits = emptyList(), tickets = emptyList()))
    private val tickets = CountingTickets { repository }

    private fun prefill(ticket: TicketDraft? = draft, seatId: Boolean = true) = VisitPrefillArgs(
        cinemaId = SampleData.DELPHI_LUX,
        date = saturday,
        filmTitle = SampleTickets.FILM,
        format = ScreeningFormat.OMU,
        startTime = SampleTickets.START,
        seatId = if (seatId) SampleTickets.row7Seat10 else null,
        ticket = ticket,
        notes = notes,
    )

    private fun viewModel(
        mode: VisitEditMode = VisitEditMode.Add(SampleTickets.HALL, prefill()),
        savedState: SavedStateHandle = handle,
    ) = VisitEditViewModel(
        mode, repository, repository, photos, savedState,
        ticketRepository = tickets, clock = clock, today = { LocalDate.of(2026, 10, 15) },
    )

    private val VisitEditViewModel.state: VisitEditUiState get() = uiState.value
    private val stored: FakeStore get() = repository.store.value
    private val row7Seat11 = SampleTickets.saal4Layout.rows[6].seatIds[10]

    @Test
    fun theFormOpensWithTheTicketItsSeatsAndTheNotes() {
        with(viewModel().state) {
            assertEquals(VisitEditStatus.Ready, status)
            assertEquals("delphi LUX", cinemaName)
            assertEquals("Saal 4", hallName)
            assertEquals(saturday, form.date)
            assertEquals(SampleTickets.START, form.startTime)
            assertEquals(SampleTickets.FILM, form.filmTitle)
            assertEquals(ScreeningFormat.OMU, form.format)
            assertEquals("Row 7 · Seat 10", seatLabel?.shortText)
            assertEquals(draft, ticketDraft)
            assertEquals(DraftQrCode.KEPT, draftQrCode)
            assertEquals(notes, importNotes)
            assertEquals(setOf(SampleTickets.row7Seat10, row7Seat11), bookingSeatIds)
            assertNull("not stored yet", ticket)
            assertFalse("the import is the starting point", hasUnsavedChanges)
        }
    }

    @Test
    fun aSeatWithoutTheHallIsNotTaken() {
        val vm = viewModel(VisitEditMode.Add(hallId = null, prefill()))
        assertNull(vm.state.form.seatId)
        assertEquals("delphi LUX", vm.state.cinemaName)
    }

    @Test
    fun saveStoresTheVisitAndItsTicketTogetherOnce() {
        val vm = viewModel()
        vm.save()
        assertTrue(vm.state.isClosed)
        val (visit, ticket) = tickets.saved.single()
        assertEquals(0L, visit.id)
        assertEquals(SampleTickets.START, visit.startTime)
        assertEquals(SampleTickets.row7Seat10, visit.seatId)
        assertEquals(Ticket(0, BookingNumber("TEST123"), seats, qr), ticket)
        val storedVisit = stored.visits.single()
        assertEquals(listOf(Ticket(storedVisit.id, BookingNumber("TEST123"), seats, qr)), stored.tickets)
    }

    @Test
    fun anExpiredQrCodeIsLeftOutOnSave() {
        val vm = viewModel()
        vm.setDate(LocalDate.of(2026, 10, 7)) // 20:15 a week before 15 Oct, 10:00 and a bit more
        assertEquals(DraftQrCode.EXPIRED, vm.state.draftQrCode)
        vm.save()
        assertNull(stored.tickets.single().qrModules)
        assertEquals(seats, stored.tickets.single().seats)
    }

    @Test
    fun aTicketWithoutQrCodeSaysSo() {
        assertEquals(DraftQrCode.NONE, viewModel(VisitEditMode.Add(SampleTickets.HALL, prefill(draft.copy(qrModules = null)))).state.draftQrCode)
    }

    @Test
    fun removeTicketDropsOnlyTheDraft() {
        val vm = viewModel()
        vm.requestRemoveTicket()
        with(vm.state) {
            assertNull("no confirmation: nothing was stored", dialog)
            assertNull(ticketDraft)
            assertEquals(VisitEditViewModel.TICKET_REMOVED, message)
            assertTrue(hasUnsavedChanges)
            assertEquals("the visit's fields stay", SampleTickets.FILM, form.filmTitle)
            assertTrue(bookingSeatIds.isEmpty())
        }
        vm.save()
        assertTrue(tickets.saved.isEmpty())
        assertEquals(1, stored.visits.size)
        assertTrue(stored.tickets.isEmpty())
    }

    @Test
    fun theDraftSurvivesProcessDeath() {
        val first = viewModel()
        first.setNote("Bring the receipt")
        val restored = viewModel()
        with(restored.state) {
            assertEquals(draft, ticketDraft)
            assertEquals(notes, importNotes)
            assertEquals("Bring the receipt", form.note)
            assertEquals(SampleTickets.row7Seat10, form.seatId)
        }
        restored.save()
        assertEquals(Ticket(0, BookingNumber("TEST123"), seats, qr), tickets.saved.single().second)
    }

    @Test
    fun aRemovedDraftStaysRemovedAfterProcessDeath() {
        viewModel().requestRemoveTicket()
        val restored = viewModel()
        assertNull(restored.state.ticketDraft)
        assertTrue(restored.state.hasUnsavedChanges)
        assertEquals(notes, restored.state.importNotes)
    }

    @Test
    fun discardDropsEverything() {
        val vm = viewModel()
        vm.requestClose()
        assertTrue("an unchanged import closes without asking", vm.state.isClosed)
        assertTrue(stored.visits.isEmpty())
        assertTrue(stored.tickets.isEmpty())
        assertNull(handle.get<String>(VisitEditViewModel.DRAFT_KEY))
    }

    @Test
    fun aBookingNumberImportedMeanwhileOpensThatVisitAndSavesNothing() {
        repository = FakeCinexRepository(SampleTickets.store())
        val visitsBefore = stored.visits
        val vm = viewModel()
        vm.onGalleryPhotoPicked("content://test.photos/picked.jpg")
        val photo = vm.state.form.photoPath!!
        vm.save()
        with(vm.state) {
            assertEquals(SampleTickets.VISIT, openImportedVisit)
            assertFalse(isClosed)
            assertFalse(isSaving)
            assertFalse(canSave)
        }
        assertEquals(visitsBefore, stored.visits)
        assertEquals(listOf(SampleTickets.ticket()), stored.tickets)
        assertTrue("the photo added here went", photo in photos.deleted)
        assertNull(handle.get<String>(VisitEditViewModel.DRAFT_KEY))
    }

    @Test
    fun withoutPrintedSeatsThePickedSeatBecomesTheTicketsSeat() {
        val vm = viewModel(VisitEditMode.Add(SampleTickets.HALL, prefill(draft.copy(seats = emptyList()), seatId = false)))
        assertTrue(vm.state.bookingSeatIds.isEmpty())
        vm.openSeatPicker()
        vm.tapPickerSeat(row7Seat11)
        vm.confirmSeat()
        vm.save()
        assertEquals(listOf(TicketSeat("7", "11")), tickets.saved.single().second.seats)
        assertTrue(vm.state.isClosed)
    }

    @Test
    fun withoutAnySeatTheVisitIsSavedAndTheTicketWaitsForOne() {
        val vm = viewModel(VisitEditMode.Add(SampleTickets.HALL, prefill(draft.copy(seats = emptyList()), seatId = false)))
        vm.save()
        with(vm.state) {
            assertEquals(VisitEditViewModel.TICKET_NEEDS_SEAT, message)
            assertFalse(isClosed)
            assertTrue(isEdit)
            assertEquals(draft.copy(seats = emptyList()), ticketDraft)
            assertTrue("the ticket isn't saved yet", hasUnsavedChanges)
        }
        val visitId = stored.visits.single().id
        assertTrue(tickets.saved.isEmpty())

        vm.openSeatPicker()
        vm.tapPickerSeat(SampleTickets.row7Seat10)
        vm.confirmSeat()
        vm.save()
        val (visit, ticket) = tickets.saved.single()
        assertEquals("the visit saved before", visitId, visit.id)
        assertEquals(Ticket(visitId, BookingNumber("TEST123"), listOf(TicketSeat("7", "10")), qr), ticket)
        assertEquals(1, stored.visits.size)
        assertTrue(vm.state.isClosed)
    }

    @Test
    fun aSeatClearedOnSaveKeepsTheStoredTicketOnTheForm() {
        val vm = viewModel()
        // The layout changed meanwhile: the seat is gone.
        val small = SeatLayout.fromPatterns(List(3) { "SSSSS" }, firstSeatId = 1)
        repository.store.value = stored.copy(halls = stored.halls.map { if (it.id == SampleTickets.HALL) it.copy(layout = small) else it })
        vm.save()
        with(vm.state) {
            assertEquals(VisitEditViewModel.SEAT_CLEARED_ON_SAVE, message)
            assertNull(ticketDraft)
            assertEquals(stored.tickets.single(), ticket)
            assertFalse(hasUnsavedChanges)
        }
    }

    @Test
    fun editSavesNeverTouchTickets() {
        repository = FakeCinexRepository(SampleTickets.store())
        val vm = viewModel(VisitEditMode.Edit(SampleTickets.VISIT))
        vm.setNote("Second row was better")
        vm.save()
        assertTrue(vm.state.isClosed)
        assertTrue(tickets.saved.isEmpty())
        assertEquals(listOf(SampleTickets.ticket()), stored.tickets)
    }

    @Test
    fun importedBeforeIsSaidOnce() {
        repository = FakeCinexRepository(SampleTickets.store())
        val vm = viewModel(VisitEditMode.Edit(SampleTickets.VISIT, importedBefore = true))
        assertEquals(VisitEditViewModel.IMPORTED_BEFORE, vm.state.message)
        vm.messageShown()
        val recreated = viewModel(VisitEditMode.Edit(SampleTickets.VISIT, importedBefore = true))
        assertNull(recreated.state.message)
        assertNull(viewModel(VisitEditMode.Edit(SampleTickets.VISIT), SavedStateHandle()).state.message)
    }

    @Test
    fun theBookingSeatsFollowTheHall() {
        val vm = viewModel()
        vm.selectHall(SampleData.DELPHI_LUX_SAAL_1)
        val labels = vm.state.labels!!
        assertEquals(setOf("Row 7 · Seat 10", "Row 7 · Seat 11"), vm.state.bookingSeatIds.map { labels.labelOf(it)!!.shortText }.toSet())
        assertNull("seats belong to one hall", vm.state.form.seatId)
    }

    @Test
    fun aStoredTicketsSeatsAreMarkedToo() {
        repository = FakeCinexRepository(SampleTickets.store())
        val vm = viewModel(VisitEditMode.Edit(SampleTickets.VISIT))
        assertEquals(setOf(SampleTickets.row7Seat10, row7Seat11), vm.state.bookingSeatIds)
    }

    /** Counts the one write the form may do with a ticket. */
    private class CountingTickets(private val repository: () -> FakeCinexRepository) : TicketRepository by DelegatingTickets(repository) {
        val saved = mutableListOf<Pair<Visit, Ticket>>()

        override suspend fun saveVisitWithTicket(visit: Visit, ticket: Ticket): SaveTicketResult =
            repository().saveVisitWithTicket(visit, ticket).also { saved += visit to ticket }
    }
}

/** A [TicketRepository] that always uses the current fake (tests replace it). */
private class DelegatingTickets(private val repository: () -> FakeCinexRepository) : TicketRepository {
    override fun observeTicket(visitId: Long) = repository().observeTicket(visitId)
    override suspend fun getTicket(visitId: Long) = repository().getTicket(visitId)
    override suspend fun findVisitId(bookingNumber: BookingNumber) = repository().findVisitId(bookingNumber)
    override suspend fun saveVisitWithTicket(visit: Visit, ticket: Ticket) = repository().saveVisitWithTicket(visit, ticket)
    override suspend fun removeTicket(visitId: Long) = repository().removeTicket(visitId)
    override suspend fun storeQrModules(visitId: Long, qrModules: io.github.katznmeik.cinexperience.core.model.QrModules) =
        repository().storeQrModules(visitId, qrModules)
    override suspend fun removeExpiredQrModules(now: java.time.Instant) = repository().removeExpiredQrModules(now)
}
