package io.github.katznmeik.cinexperience.feature.visits.list

import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.MutableClock
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.feature.visits.UpcomingVisitFixtures
import io.github.katznmeik.cinexperience.feature.visits.VisitFixtures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** All visits (spec §6.8): upcoming visits first in their own section, soonest first, with the start time. */
class AllVisitsUpcomingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = MutableClock(SampleProgram.FETCHED_AT)
    private val ticks = MutableStateFlow(0)

    private fun TestScope.viewModel(): AllVisitsViewModel {
        val vm = AllVisitsViewModel(FakeCinexRepository(UpcomingVisitFixtures.store()), clock, ticks.map { })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun upcomingVisitsComeFirstSoonestFirstAndTheOthersStayNewestFirst() = runTest {
        val state = viewModel().uiState.value
        assertEquals(listOf(UpcomingVisitFixtures.TOMORROW_VISIT, SampleTickets.VISIT), state.upcoming.map { it.visit.id })
        assertEquals(listOf(9_001L, 9_004, 9_002, 9_005, 9_003), state.visits.map { it.visit.id })
        assertEquals(7, state.count)
        assertEquals("7 visits · 2 upcoming", state.subtitle())
    }

    @Test
    fun upcomingRowsShowTheDayAndStartTime() = runTest {
        val state = viewModel().uiState.value
        val today = state.today!!
        assertEquals("Tomorrow · 18:00 · OV", state.upcoming[0].upcomingDetailText(today))
        assertEquals("Sat 17 Oct · 20:15 · Row 7 · Seat 10 · OmU", state.upcoming[1].upcomingDetailText(today))
        assertEquals("Babylon Kreuzberg · Saal 1", state.upcoming[0].placeText())
    }

    @Test
    fun aVisitLeavesUpcomingOnceItStarts() = runTest {
        val vm = viewModel()
        clock.now = Instant.parse("2026-10-16T16:00:00Z") // Friday 18:00 in Berlin: "Paper Lanterns" starts
        ticks.value++
        val state = vm.uiState.value
        assertEquals(listOf(SampleTickets.VISIT), state.upcoming.map { it.visit.id })
        assertEquals(UpcomingVisitFixtures.TOMORROW_VISIT, state.visits.first().visit.id) // the newest of the others
        assertEquals("Tomorrow · 20:15 · Row 7 · Seat 10 · OmU", state.upcoming.single().upcomingDetailText(state.today!!))
    }

    @Test
    fun withoutUpcomingVisitsTheListIsAsBefore() = runTest {
        val vm = AllVisitsViewModel(FakeCinexRepository(VisitFixtures.store()), clock, ticks.map { })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val state = vm.uiState.value
        assertTrue(state.upcoming.isEmpty())
        assertEquals("5 visits · newest first", state.subtitle())
    }
}
