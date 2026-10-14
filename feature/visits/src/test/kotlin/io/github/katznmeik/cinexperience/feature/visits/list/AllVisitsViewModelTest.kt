package io.github.katznmeik.cinexperience.feature.visits.list

import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import io.github.katznmeik.cinexperience.feature.visits.VisitFixtures
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class AllVisitsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun listsAllVisitsNewestFirstWithTheirTexts() = runTest {
        val vm = AllVisitsViewModel(FakeCinexRepository(VisitFixtures.store()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(listOf(9_001L, 9_004, 9_002, 9_005, 9_003), state.visits.map { it.visit.id })
        val first = state.visits.first()
        assertEquals("delphi LUX · Saal 1", first.placeText())
        assertEquals("3 Oct 2026 · Row 7 · Seat 10 · OmU", first.detailText())
        assertEquals("30 Aug 2026 · Row 5 · Seat 3", state.visits.last().detailText())
        assertEquals("5 Sep 2026 · German dub", state.visits[3].detailText())
    }

    @Test
    fun aSeatInARowWithoutANameIsShownBySeatAlone() = runTest {
        val store = SampleData.storeWithKantKinoSaal3()
        val visit = Visit(
            id = 9_100, hallId = SampleData.KANT_KINO_SAAL_3, date = LocalDate.of(2026, 10, 4),
            seatId = SampleLayouts.kantKinoSaal3Seat12, screeningFormat = ScreeningFormat.OMU,
        )
        val vm = AllVisitsViewModel(FakeCinexRepository(store.copy(visits = listOf(visit))))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val item = vm.uiState.value.visits.single()
        assertEquals("Kant Kino · Saal 3", item.placeText())
        assertEquals("4 Oct 2026 · Seat 12 · OmU", item.detailText())
    }

    @Test
    fun emptyAndErrorStates() = runTest {
        val empty = AllVisitsViewModel(FakeCinexRepository(SampleData.freshInstallStore()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { empty.uiState.collect {} }
        assertTrue(empty.uiState.value.visits.isEmpty())
        assertFalse(empty.uiState.value.isLoading)

        val failing = FakeCinexRepository(SampleData.store()).apply { observeFailure = IOException("db") }
        val broken = AllVisitsViewModel(failing)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { broken.uiState.collect {} }
        assertEquals(AllVisitsViewModel.LOAD_FAILED, broken.uiState.value.errorMessage)
    }
}
