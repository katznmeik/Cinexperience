package io.github.katznmeik.cinexperience.feature.seatmap.detail

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.seatmap.RatedHallFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The selected-seat card on the hall detail: colour override buttons and the favourite toggle. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class SelectedSeatCardUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val auto = "Seat colour: Auto, from ratings"
    private val great = "Seat colour: Great"
    private val ok = "Seat colour: OK"
    private val avoid = "Seat colour: Avoid"

    private fun click(description: String) {
        composeRule.onNodeWithContentDescription(description).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun buttonsCallTheOverrideAndFavouriteCallbacks() {
        val repository = FakeCinexRepository(RatedHallFixture.store())
        val viewModel = HallDetailViewModel(RatedHallFixture.HALL, repository, repository)
        viewModel.selectSeat(RatedHallFixture.manualAvoid)
        val state = runBlocking { viewModel.uiState.first { it is HallDetailUiState.Loaded } }
        val colours = mutableListOf<Pair<SeatId, SeatColour?>>()
        val favourites = mutableListOf<SeatId>()
        composeRule.setContent {
            CinexTheme {
                HallDetailScreen(
                    state = state, onBack = {}, onEditLayout = {}, onAddVisit = {}, onOpenVisit = {},
                    onSeatClick = {}, onSeatChosen = {},
                    onSetSeatColour = { seat, colour -> colours += seat to colour },
                    onToggleFavourite = { favourites += it },
                )
            }
        }
        // The seat is set to "avoid" manually: only that option is selected.
        composeRule.onNodeWithContentDescription(avoid).performScrollTo().assertIsSelected()
        listOf(auto, great, ok).forEach { composeRule.onNodeWithContentDescription(it).assertIsNotSelected() }

        listOf(auto, great, ok, avoid).forEach(::click)
        val seat = RatedHallFixture.manualAvoid
        assertEquals(listOf(seat to null, seat to SeatColour.GREEN, seat to SeatColour.YELLOW, seat to SeatColour.RED), colours)

        composeRule.onNodeWithText("Mark as favourite").performScrollTo().performClick()
        assertEquals(listOf(seat), favourites)
    }

    @Test
    fun theCardFollowsTheRepository() {
        val repository = FakeCinexRepository(RatedHallFixture.store())
        val viewModel = HallDetailViewModel(RatedHallFixture.HALL, repository, repository)
        val seat = RatedHallFixture.ratedOk
        viewModel.selectSeat(seat)
        composeRule.setContent {
            CinexTheme { HallDetailEntry(viewModel, onBack = {}, onEditLayout = {}, onAddVisit = {}, onOpenVisit = {}) }
        }
        fun colourOf(id: SeatId): SeatColour? = repository.store.value.overrides.firstOrNull { it.seatId == id }?.colour

        composeRule.onNodeWithContentDescription(auto).performScrollTo().assertIsSelected()
        click(great)
        assertEquals(SeatColour.GREEN, colourOf(seat))
        composeRule.onNodeWithContentDescription(great).assertIsSelected()
        composeRule.onNodeWithContentDescription(auto).assertIsNotSelected()
        click(ok)
        assertEquals(SeatColour.YELLOW, colourOf(seat))
        click(avoid)
        assertEquals(SeatColour.RED, colourOf(seat))
        click(auto)
        assertNull(colourOf(seat))
        composeRule.onNodeWithContentDescription(auto).assertIsSelected()

        // The fixture's favourite is another seat: marking this one moves it here.
        composeRule.onNodeWithText("Mark as favourite").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(seat), repository.store.value.overrides.filter { it.isFavourite }.map { it.seatId })
        composeRule.onNodeWithText("Remove favourite").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(emptyList<SeatId>(), repository.store.value.overrides.filter { it.isFavourite }.map { it.seatId })
        composeRule.onNodeWithText("Mark as favourite").assertExists()
    }
}
