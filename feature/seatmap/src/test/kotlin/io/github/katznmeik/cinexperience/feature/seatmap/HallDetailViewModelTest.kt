package io.github.katznmeik.cinexperience.feature.seatmap

import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HallDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(SampleData.store())

    @Test
    fun showsHallAndCinemaName() = runTest {
        val vm = HallDetailViewModel(SampleData.DELPHI_LUX_SAAL_1, repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(HallDetailUiState.Loaded("Saal 1", "delphi LUX"), vm.uiState.value)
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
