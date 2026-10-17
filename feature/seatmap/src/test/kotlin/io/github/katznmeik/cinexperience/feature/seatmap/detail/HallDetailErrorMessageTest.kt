package io.github.katznmeik.cinexperience.feature.seatmap.detail

import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.model.HallDetail
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The hall detail's one-off message in the error state (see also [HallDetailMessageTest]). */
class HallDetailErrorMessageTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinexRepository(SampleData.storeWithSplitHall())

    /** Set to make the hall's flow fail while the screen is open. */
    private val loadFails = MutableStateFlow(false)

    private val halls = object : HallRepository by repository {
        override fun observeHallDetail(hallId: Long): Flow<HallDetail?> =
            combine(repository.observeHallDetail(hallId), loadFails) { detail, fails ->
                if (fails) throw IllegalStateException("db") else detail
            }
    }

    private val seat = SampleLayouts.splitHall.rows[6].seatIds[13]

    private fun TestScope.viewModel(): HallDetailViewModel {
        val vm = HallDetailViewModel(SampleData.DELPHI_LUX_SAAL_1, halls, repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun aPendingMessageStaysWhenLoadingFailsUntilItWasShown() = runTest {
        val vm = viewModel()
        repository.failure = IllegalStateException("disk full")
        vm.setSeatColour(seat, SeatColour.RED)
        assertTrue(vm.uiState.value is HallDetailUiState.Loaded)
        assertEquals(HallDetailViewModel.COLOUR_FAILED, vm.uiState.value.snackbarMessage)

        loadFails.value = true
        assertEquals(
            HallDetailUiState.Error(HallDetailViewModel.LOAD_FAILED, pendingMessage = HallDetailViewModel.COLOUR_FAILED),
            vm.uiState.value,
        )
        vm.messageShown()
        assertEquals(HallDetailUiState.Error(HallDetailViewModel.LOAD_FAILED), vm.uiState.value)
        assertNull(vm.uiState.value.snackbarMessage)
    }

    @Test
    fun aWriteThatFailsAfterLoadingFailedStillReportsIt() = runTest {
        val vm = viewModel()
        loadFails.value = true
        assertEquals(HallDetailUiState.Error(HallDetailViewModel.LOAD_FAILED), vm.uiState.value)

        repository.failure = IllegalStateException("disk full")
        vm.setSeatColour(seat, SeatColour.GREEN)
        assertEquals(HallDetailViewModel.COLOUR_FAILED, vm.uiState.value.snackbarMessage)
    }
}
