package io.github.katznmeik.cinexperience.feature.tickets

import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.qrModulesExpireAt
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.MutableClock
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** The ticket screen's states (spec §6.11): QR code, removed or missing QR code, no ticket. */
class TicketViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Thursday 15 Oct 2026, 10:00 in Berlin; the showing is on Saturday 17 Oct at 20:15. */
    private val clock = MutableClock(SampleProgram.FETCHED_AT)
    private val ticks = MutableStateFlow(0)
    private val visit = SampleTickets.visit(SampleTickets.dateAfter(SampleProgram.FETCHED_AT, 2))

    private fun TestScope.viewModel(store: FakeStore = SampleTickets.store(), repository: FakeCinexRepository = FakeCinexRepository(store)): TicketViewModel {
        val vm = TicketViewModel(SampleTickets.VISIT, repository, repository, repository, clock, ticks.map { })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private fun TicketViewModel.shown(): TicketDetails = (uiState.value as TicketContent.Shown).ticket

    private fun tick(at: Instant) {
        clock.now = at
        ticks.value++
    }

    @Test
    fun showsTheQrCodeWithTheShowingSeatsAndBookingNumber() = runTest {
        val ticket = viewModel().shown()
        assertEquals(SampleTickets.qrModules(), ticket.qrModules)
        assertNull(ticket.missingQrCode)
        assertEquals(SampleTickets.FILM, ticket.filmTitle)
        assertEquals("Saturday 17 Oct · 20:15", ticket.showingText())
        assertEquals("delphi LUX · Saal 4", ticket.placeText())
        assertEquals("TEST123", ticket.bookingNumber.value)
        assertEquals(
            listOf(TicketSeatLine(SeatLabel("7", "10"), isOwn = true), TicketSeatLine(SeatLabel("7", "11"), isOwn = false)),
            ticket.seats,
        )
    }

    @Test
    fun onTheDayItSaysToday() = runTest {
        clock.now = Instant.parse("2026-10-17T16:00:00Z")
        assertEquals("Today · Saturday 17 Oct · 20:15", viewModel().shown().showingText())
    }

    @Test
    fun anExpiredQrCodeIsNotShownEvenBeforeTheStartupRemoval() = runTest {
        val expiry = visit.qrModulesExpireAt // 7 days after the start
        clock.now = expiry.minusSeconds(1)
        assertEquals(SampleTickets.qrModules(), viewModel().shown().qrModules)

        clock.now = expiry
        val ticket = viewModel().shown()
        assertNull(ticket.qrModules)
        assertEquals(MissingQrCode.REMOVED_AFTER_SHOWING, ticket.missingQrCode)
        assertEquals("TEST123", ticket.bookingNumber.value)
    }

    @Test
    fun theQrCodeGoesWhenItExpiresWhileTheScreenIsOpen() = runTest {
        clock.now = visit.qrModulesExpireAt.minus(Duration.ofMinutes(1))
        val vm = viewModel()
        assertEquals(SampleTickets.qrModules(), vm.shown().qrModules)
        tick(visit.qrModulesExpireAt)
        assertNull(vm.shown().qrModules)
        assertEquals(MissingQrCode.REMOVED_AFTER_SHOWING, vm.shown().missingQrCode)
    }

    @Test
    fun aRemovedQrCodeAfterTheShowing() = runTest {
        clock.now = visit.qrModulesExpireAt.plus(Duration.ofDays(3))
        val ticket = viewModel(SampleTickets.store(tickets = listOf(SampleTickets.ticket(qr = null)))).shown()
        assertNull(ticket.qrModules)
        assertEquals(MissingQrCode.REMOVED_AFTER_SHOWING, ticket.missingQrCode)
    }

    @Test
    fun aTicketWithoutQrCodeBeforeTheShowingWasNotStored() = runTest {
        // E.g. restored from a backup, which never holds QR codes.
        val ticket = viewModel(SampleTickets.store(tickets = listOf(SampleTickets.ticket(qr = null)))).shown()
        assertNull(ticket.qrModules)
        assertEquals(MissingQrCode.NOT_STORED, ticket.missingQrCode)
    }

    @Test
    fun theVisitsOwnSeatIsMarkedOnlyWhenItIsOneOfTheBooking() = runTest {
        val elsewhere = visit.copy(seatId = SampleTickets.saal4Layout.rows[7].seatIds[3]) // Row 8 · Seat 4
        val ticket = viewModel(SampleTickets.store(visits = listOf(elsewhere))).shown()
        assertEquals(listOf(false, false), ticket.seats.map { it.isOwn })

        val noSeat = viewModel(SampleTickets.store(visits = listOf(visit.copy(seatId = null)))).shown()
        assertEquals(listOf(false, false), noSeat.seats.map { it.isOwn })
    }

    @Test
    fun withoutTicketOrVisitTheTicketIsNotFound() = runTest {
        assertEquals(TicketContent.NotFound, viewModel(SampleTickets.store(tickets = emptyList())).uiState.value)
        assertEquals(TicketContent.NotFound, viewModel(SampleTickets.store(visits = emptyList(), tickets = emptyList())).uiState.value)
    }

    @Test
    fun aTicketRemovedWhileShownIsNotFound() = runTest {
        val repository = FakeCinexRepository(SampleTickets.store())
        val vm = viewModel(repository = repository)
        assertEquals(SampleTickets.qrModules(), vm.shown().qrModules)
        repository.removeTicket(SampleTickets.VISIT)
        assertEquals(TicketContent.NotFound, vm.uiState.value)
    }

    @Test
    fun aLoadFailureIsAMessage() = runTest {
        val failing = FakeCinexRepository(SampleTickets.store()).apply { observeFailure = IOException("db") }
        assertEquals(TicketContent.Error(TicketViewModel.LOAD_FAILED), viewModel(repository = failing).uiState.value)
    }
}
