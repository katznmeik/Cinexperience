package io.github.katznmeik.cinexperience.feature.seatmap.detail

import io.github.katznmeik.cinexperience.core.data.repository.SeatOverrideRepository
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Taps on the seat card's favourite button (spec §6.3) that come faster than the
 * database: the writes run one after another, and each tap toggles what the one
 * before it wrote.
 */
class FavouriteSeatDoubleTapTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fake = FakeCinexRepository(SampleData.storeWithSplitHall())
    private val overrides = SlowOverrideRepository(fake)
    private val hallId = SampleData.DELPHI_LUX_SAAL_1
    private val first = SampleLayouts.splitHall.rows[6].seatIds[13]
    private val second = SampleLayouts.splitHall.rows[4].seatIds[2]

    private fun TestScope.viewModel(): HallDetailViewModel {
        val vm = HallDetailViewModel(hallId, fake, overrides)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private fun HallDetailViewModel.favourites(): Set<SeatId> =
        (uiState.value as HallDetailUiState.Loaded).seatVisuals.filterValues { it.isFavourite }.keys

    @Test
    fun aDoubleTapBeforeTheFirstWriteEndsWithoutAFavourite() = runTest {
        val vm = viewModel()
        assertTrue(vm.favourites().isEmpty())

        vm.toggleFavourite(first)
        vm.toggleFavourite(first) // the map still shows no favourite: the first write hasn't finished
        assertTrue(vm.favourites().isEmpty())

        overrides.gate.complete(Unit)
        assertEquals(listOf(first, null), overrides.favouriteWrites)
        assertTrue(vm.favourites().isEmpty())
        assertNull((vm.uiState.value as HallDetailUiState.Loaded).message)
    }

    @Test
    fun quickTapsOnTwoSeatsMoveTheFavouriteAndColoursKeepTheirOrder() = runTest {
        val vm = viewModel()
        vm.toggleFavourite(first)
        vm.toggleFavourite(second)
        vm.setSeatColour(second, SeatColour.RED)
        vm.setSeatColour(second, SeatColour.GREEN)

        overrides.gate.complete(Unit)
        assertEquals(listOf(first, second), overrides.favouriteWrites)
        assertEquals(setOf(second), vm.favourites())
        assertEquals(SeatColour.GREEN, fake.store.value.overrides.single { it.seatId == second }.colour)
    }

    /** Delegates to the fake but holds every write until [gate] completes. */
    private class SlowOverrideRepository(private val delegate: FakeCinexRepository) : SeatOverrideRepository by delegate {
        val gate = CompletableDeferred<Unit>()
        val favouriteWrites = mutableListOf<SeatId?>()

        override suspend fun setFavouriteSeat(hallId: Long, seatId: SeatId?) {
            gate.await()
            favouriteWrites += seatId
            delegate.setFavouriteSeat(hallId, seatId)
        }

        override suspend fun setSeatColour(hallId: Long, seatId: SeatId, colour: SeatColour?) {
            gate.await()
            delegate.setSeatColour(hallId, seatId, colour)
        }
    }
}
