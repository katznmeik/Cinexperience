package io.github.katznmeik.cinexperience.feature.seatmap.detail

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.model.HallDetail
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A one-off message of the hall detail (e.g. a failed save) still shows when the
 * hall can't be loaded any more: the message isn't part of the loaded state only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class HallDetailMessageTest {

    @get:Rule
    val composeRule = createComposeRule()

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

    private fun showHall(): HallDetailViewModel {
        val viewModel = HallDetailViewModel(SampleData.DELPHI_LUX_SAAL_1, halls, repository)
        composeRule.setContent {
            CinexTheme {
                HallDetailEntry(viewModel, onBack = {}, onEditLayout = {}, onAddVisit = {}, onOpenVisit = {})
            }
        }
        composeRule.waitForIdle()
        return viewModel
    }

    @Test
    fun aFailedSaveStillShowsWhenLoadingFailsToo() {
        val viewModel = showHall()
        repository.failure = IllegalStateException("disk full")
        composeRule.runOnIdle { viewModel.setSeatColour(seat, SeatColour.RED) }
        composeRule.onNodeWithText(HallDetailViewModel.COLOUR_FAILED).assertExists()

        loadFails.value = true
        composeRule.onNodeWithText(HallDetailViewModel.LOAD_FAILED).assertExists()
        composeRule.onNodeWithText(HallDetailViewModel.COLOUR_FAILED).assertExists()
    }
}
