package io.github.katznmeik.cinexperience.feature.cinemas

import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.MutableClock
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.feature.cinemas.list.NextVisit
import io.github.katznmeik.cinexperience.feature.cinemas.list.NextVisitViewModel
import io.github.katznmeik.cinexperience.feature.cinemas.list.description
import io.github.katznmeik.cinexperience.feature.cinemas.list.placeText
import io.github.katznmeik.cinexperience.feature.cinemas.list.whenText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalTime

/** Which visit the Next visit card shows (spec §6.1, §4.4) and when it shows "Show ticket". */
class NextVisitViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Thursday 15 Oct 2026, 10:00 in Berlin. */
    private val clock = MutableClock(SampleProgram.FETCHED_AT)
    private val ticks = MutableStateFlow(0)
    private val today = SampleTickets.dateAfter(SampleProgram.FETCHED_AT, 0)

    private fun visit(id: Long, daysAhead: Long, start: LocalTime?, film: String = "Film $id"): Visit =
        SampleTickets.visit(today.plusDays(daysAhead), id = id, startTime = start).copy(filmTitle = film)

    private fun TestScope.viewModel(store: FakeStore, repository: FakeCinexRepository = FakeCinexRepository(store)): NextVisitViewModel {
        val vm = NextVisitViewModel(repository, repository, clock, ticks.map { })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private fun store(vararg visits: Visit) = SampleTickets.store(visits = visits.toList(), tickets = emptyList())

    private val NextVisitViewModel.card: NextVisit? get() = uiState.value

    @Test
    fun theSoonestUpcomingVisitWithItsTexts() = runTest {
        val vm = viewModel(store(visit(1, 2, LocalTime.of(20, 15)), visit(2, 1, LocalTime.of(18, 0), film = SampleTickets.FILM)))
        val card = vm.card!!
        assertEquals(2L, card.visitId)
        assertEquals(SampleTickets.FILM, card.filmTitle)
        assertEquals("Tomorrow · 18:00", card.whenText())
        assertEquals("delphi LUX · Saal 4", card.placeText())
        assertEquals(SeatLabel("7", 10), card.seatLabel)
        assertEquals(
            "Next visit: \u2068The Quiet Projectionist\u2069, Tomorrow · 18:00, delphi LUX · Saal 4, Row 7, Seat 10",
            card.description(),
        )
        assertFalse(card.canShowTicket)
    }

    @Test
    fun equalShowingsTakeTheVisitSavedFirst() = runTest {
        val evening = LocalTime.of(20, 15)
        assertEquals(4L, viewModel(store(visit(9, 1, evening), visit(4, 1, evening))).card?.visitId)
    }

    @Test
    fun aVisitWithoutAStartTimeComesAfterTheTimedOnesOfItsDay() = runTest {
        assertEquals(2L, viewModel(store(visit(1, 1, null), visit(2, 1, LocalTime.of(22, 30)))).card?.visitId)

        val untimed = viewModel(store(visit(3, 2, null))).card!!
        assertEquals(3L, untimed.visitId)
        assertNull(untimed.startTime)
        assertEquals("Sat 17 Oct", untimed.whenText())
    }

    @Test
    fun pastVisitsAndShowingsStartedHalfAnHourAgoShowNoCard() = runTest {
        assertNull(viewModel(store()).card)
        // Yesterday; today at 9:30 (started 30 minutes ago at 10:00); yesterday without a start time.
        assertNull(viewModel(store(visit(1, -1, LocalTime.of(20, 15)), visit(2, 0, LocalTime.of(9, 30)), visit(3, -1, null))).card)
    }

    @Test
    fun aShowingThatStartedLessThanHalfAnHourAgoStaysAsStarted() = runTest {
        // 10:00 in Berlin: the 9:45 showing started 15 minutes ago and comes before the 11:00 one.
        val card = viewModel(store(visit(1, 0, LocalTime.of(9, 45)), visit(2, 0, LocalTime.of(11, 0)))).card!!
        assertEquals(1L, card.visitId)
        assertTrue(card.hasStarted)
        assertEquals("Started 09:45", card.whenText())
    }

    @Test
    fun todaysVisitWithoutAStartTimeStaysAfterTheTimedOnesUntilMidnight() = runTest {
        val vm = viewModel(store(visit(1, 0, null), visit(2, 0, LocalTime.of(20, 15))))
        assertEquals(2L, vm.card?.visitId)
        clock.now = Instant.parse("2026-10-15T18:45:00Z") // 20:45: the 20:15 showing leaves
        ticks.value++
        assertEquals(1L, vm.card?.visitId)
        assertEquals("Today", vm.card?.whenText())
        assertFalse(vm.card!!.hasStarted)
        clock.now = Instant.parse("2026-10-15T22:00:00Z") // midnight in Berlin
        ticks.value++
        assertNull(vm.card)
    }

    @Test
    fun theCardFollowsTheClock() = runTest {
        // Today at 10:30 and 20:15, then nothing.
        val vm = viewModel(store(visit(1, 0, LocalTime.of(10, 30)), visit(2, 0, LocalTime.of(20, 15))))
        assertEquals(1L, vm.card?.visitId)
        assertEquals("Today · 10:30", vm.card?.whenText())

        clock.now = Instant.parse("2026-10-15T08:30:00Z") // 10:30 in Berlin: the first one starts
        assertFalse(vm.card!!.hasStarted) // until the next tick
        ticks.value++
        assertEquals(1L, vm.card?.visitId)
        assertEquals("Started 10:30", vm.card?.whenText())

        clock.now = Instant.parse("2026-10-15T08:59:00Z") // 10:59: still on the card
        ticks.value++
        assertEquals(1L, vm.card?.visitId)
        clock.now = Instant.parse("2026-10-15T09:00:00Z") // 11:00: half an hour after the start
        ticks.value++
        assertEquals(2L, vm.card?.visitId)

        clock.now = Instant.parse("2026-10-15T18:45:00Z") // 20:45: the second one leaves too
        ticks.value++
        assertNull(vm.card)
    }

    @Test
    fun showTicketStaysWhileTheStartedShowingIsOnTheCard() = runTest {
        val showing = SampleTickets.visit(today) // today at 20:15, with its ticket and QR code
        val vm = viewModel(SampleTickets.store(visits = listOf(showing)))
        clock.now = Instant.parse("2026-10-15T18:40:00Z") // 20:40 in Berlin
        ticks.value++
        assertEquals("Started 20:15", vm.card?.whenText())
        assertTrue(vm.card!!.canShowTicket)
    }

    @Test
    fun aTomorrowCardBecomesTodayAtMidnight() = runTest {
        val vm = viewModel(store(visit(1, 1, LocalTime.of(20, 15))))
        assertEquals("Tomorrow · 20:15", vm.card?.whenText())
        clock.now = Instant.parse("2026-10-15T22:00:00Z") // midnight in Berlin (summer time)
        ticks.value++
        assertEquals("Today · 20:15", vm.card?.whenText())
    }

    @Test
    fun showTicketOnlyWithAQrCode() = runTest {
        val upcoming = SampleTickets.visit(today.plusDays(2))
        val repository = FakeCinexRepository(SampleTickets.store(visits = listOf(upcoming)))
        val vm = viewModel(repository.store.value, repository)
        assertTrue(vm.card!!.canShowTicket)

        repository.store.value = repository.store.value.copy(tickets = listOf(SampleTickets.ticket(qr = null)))
        assertFalse("a ticket without QR code", vm.card!!.canShowTicket)

        repository.removeTicket(SampleTickets.VISIT)
        assertFalse("no ticket", vm.card!!.canShowTicket)
        assertEquals(SampleTickets.VISIT, vm.card?.visitId)

        repository.store.value = repository.store.value.copy(tickets = listOf(SampleTickets.ticket()))
        assertTrue("a ticket imported later", vm.card!!.canShowTicket)
    }

    @Test
    fun aLoadFailureShowsNoCard() = runTest {
        val failing = FakeCinexRepository(SampleTickets.store()).apply { observeFailure = IOException("db") }
        assertNull(viewModel(failing.store.value, failing).card)
    }
}
