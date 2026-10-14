package io.github.katznmeik.cinexperience.feature.seatmap.detail

import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import kotlinx.coroutines.launch
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

class HallDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(SampleData.storeWithSplitHall())

    private fun kotlinx.coroutines.test.TestScope.loaded(hallId: Long): Pair<HallDetailViewModel, () -> HallDetailUiState.Loaded> {
        val vm = HallDetailViewModel(hallId, repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm to { vm.uiState.value as HallDetailUiState.Loaded }
    }

    @Test
    fun showsTheHallWithItsLayout() = runTest {
        val (_, state) = loaded(SampleData.DELPHI_LUX_SAAL_1)
        with(state()) {
            assertEquals("Saal 1", hallName)
            assertEquals("delphi LUX", cinemaName)
            assertEquals(SampleLayouts.splitHall, layout)
            assertEquals(16f, screenWidth)
            assertEquals(4, visitCount)
            assertFalse(layoutUnreadable)
            assertEquals("delphi LUX · 183 seats · 4 visits", hallMetaText(this))
            assertNull(selectedSeat)
        }
    }

    @Test
    fun tappingASeatSelectsItAndTappingAgainClearsIt() = runTest {
        val (vm, state) = loaded(SampleData.DELPHI_LUX_SAAL_1)
        val seat = SampleLayouts.splitHall.rows[6].seatIds[8] // row 7, centre block
        vm.onSeatClick(seat)
        val selected = assertNotNullSeat(state().selectedSeat)
        assertEquals(SeatLabel("7", 5), selected.label)
        assertNotNull(selected.viewingAngle) // the hall has screen width and distance
        vm.onSeatClick(seat)
        assertNull(state().selectedSeat)
        vm.selectSeat(seat)
        vm.selectSeat(seat) // choosing from the picker never toggles
        assertEquals(seat, state().selectedSeat?.seatId)
        vm.clearSelection()
        assertNull(state().selectedSeat)
    }

    private fun assertNotNullSeat(seat: SelectedSeat?): SelectedSeat {
        assertNotNull(seat)
        return seat!!
    }

    @Test
    fun hallWithoutLayoutOrScreenGeometry() = runTest {
        val (vm, state) = loaded(SampleData.DELPHI_LUX_SAAL_6)
        assertNull(state().layout)
        assertEquals("delphi LUX · no visits", hallMetaText(state()))
        vm.onSeatClick(SeatId(1)) // no layout: nothing to select
        assertNull(state().selectedSeat)

        val (other, otherState) = loaded(502)
        val seat = SampleData.halls.single { it.id == 502L }.layout!!.seatIds.first()
        other.onSeatClick(seat)
        assertNull(otherState().selectedSeat!!.viewingAngle) // no screen geometry stored
    }

    @Test
    fun unreadableLayoutsAreFlagged() = runTest {
        repository.store.value = repository.store.value.copy(
            halls = repository.store.value.halls.map {
                if (it.id == SampleData.DELPHI_LUX_SAAL_6) it.copy(layoutUnreadable = true) else it
            },
        )
        val (_, state) = loaded(SampleData.DELPHI_LUX_SAAL_6)
        assertTrue(state().layoutUnreadable)
        assertNull(state().layout)
    }

    @Test
    fun listsTheVisitsNewestFirstWithSeatLabels() = runTest {
        val seat = SampleLayouts.splitHall.rows[6].seatIds[13] // row 7, seat 10
        val newest = Visit(
            id = 800,
            hallId = SampleData.DELPHI_LUX_SAAL_1,
            date = LocalDate.of(2026, 10, 3),
            filmTitle = "Perfect Days",
            seatId = seat,
            screeningFormat = ScreeningFormat.OMU,
            seatRating = Rating(4),
        )
        val removedSeat = Visit(id = 801, hallId = SampleData.DELPHI_LUX_SAAL_1, date = LocalDate.of(2026, 10, 2), seatId = SeatId(9_999))
        repository.store.value = repository.store.value.copy(visits = repository.store.value.visits + removedSeat + newest)
        val (_, state) = loaded(SampleData.DELPHI_LUX_SAAL_1)
        val visits = state().visits
        assertEquals(6, visits.size)
        assertEquals(listOf(800L, 801L), visits.take(2).map { it.visitId })
        assertEquals(visits.sortedByDescending { it.date }.map { it.date }, visits.map { it.date })
        with(visits[0]) {
            assertEquals(SeatLabel("7", 10), seatLabel)
            assertEquals("3 Oct 2026 · Row 7 · Seat 10 · OmU", detailText())
        }
        assertNull(visits[1].seatLabel) // its seat is no longer in the layout
        assertEquals("2 Oct 2026", visits[1].detailText())
    }

    @Test
    fun missingHallAndErrors() = runTest {
        val missing = HallDetailViewModel(999, repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { missing.uiState.collect {} }
        assertEquals(HallDetailUiState.NotFound, missing.uiState.value)

        repository.observeFailure = IllegalStateException("db")
        val failing = HallDetailViewModel(SampleData.DELPHI_LUX_SAAL_1, repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { failing.uiState.collect {} }
        assertEquals(HallDetailUiState.Error(HallDetailViewModel.LOAD_FAILED), failing.uiState.value)
    }
}
