package io.github.katznmeik.cinexperience.feature.cinemas

import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailContent
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Taps on the ☆/★ toggle (spec §6.2) that come faster than the database: each tap
 * toggles what the tap before it wrote, so an on-off double tap ends "off".
 */
class FavouriteDoubleTapTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fake = FakeCinexRepository(SampleData.store())
    private val repository = SlowFavouriteRepository(fake)

    private fun isFavourite() = fake.store.value.cinemas.first { it.id == SampleData.DELPHI_LUX }.isFavourite

    @Test
    fun aDoubleTapBeforeTheFirstWriteEndsWhereItStarted() = runTest {
        val vm = CinemaDetailViewModel(SampleData.DELPHI_LUX, repository, fake, FakeProgramRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val shown = { (vm.uiState.value.content as CinemaDetailContent.Loaded).detail.cinema.isFavourite }
        assertFalse(shown())

        vm.toggleFavourite()
        vm.toggleFavourite() // the screen still shows ☆: the first write hasn't finished
        assertFalse(shown())

        repository.gate.complete(Unit)
        assertEquals(listOf(true, false), repository.writes)
        assertFalse(isFavourite())
        assertFalse(shown())
        assertNull(vm.uiState.value.message)

        // Three quick taps end "on".
        vm.toggleFavourite()
        vm.toggleFavourite()
        vm.toggleFavourite()
        assertTrue(isFavourite())
        assertTrue(shown())
    }

    /** Delegates to the fake but holds [setFavourite] until [gate] completes. */
    private class SlowFavouriteRepository(private val delegate: FakeCinexRepository) : CinemaRepository by delegate {
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<Boolean>()

        override suspend fun setFavourite(cinemaId: Long, favourite: Boolean): Boolean {
            gate.await()
            writes += favourite
            return delegate.setFavourite(cinemaId, favourite)
        }
    }
}
