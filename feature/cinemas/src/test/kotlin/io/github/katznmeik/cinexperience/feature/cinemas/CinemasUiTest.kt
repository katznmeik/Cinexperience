package io.github.katznmeik.cinexperience.feature.cinemas

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailEntry
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailViewModel
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasEntry
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasViewModel
import io.github.katznmeik.cinexperience.feature.cinemas.list.NO_FAVOURITES_TEXT
import io.github.katznmeik.cinexperience.feature.cinemas.list.NextVisitViewModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.ZoneOffset

/** The favourite toggle and the Favourites chip, driven through the real ViewModels. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class CinemasUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val repository = FakeCinexRepository(SampleData.store())

    private fun showDetail() {
        val viewModel = CinemaDetailViewModel(SampleData.DELPHI_LUX, repository, repository, FakeProgramRepository())
        composeRule.setContent {
            CinexTheme { CinemaDetailEntry(viewModel, onBack = {}, onOpenHall = {}, onCinemaDeleted = {}) }
        }
    }

    private fun isFavourite(id: Long) = repository.store.value.cinemas.first { it.id == id }.isFavourite

    @Test
    fun favouriteToggleIsANamedToggleThatWritesAndUpdates() {
        showDetail()
        val toggle = composeRule.onNodeWithContentDescription("Favourite")
        toggle
            .assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .performClick()
        composeRule.waitForIdle()
        assertTrue(isFavourite(SampleData.DELPHI_LUX))
        toggle.assertIsOn()

        toggle.performClick()
        composeRule.waitForIdle()
        assertEquals(false, isFavourite(SampleData.DELPHI_LUX))
        toggle.assertIsOff()
    }

    @Test
    fun failedFavouriteWriteShowsMessage() {
        showDetail()
        repository.failure = IllegalStateException("disk full")
        composeRule.onNodeWithContentDescription("Favourite").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(CinemaDetailViewModel.FAVOURITE_FAILED).assertExists()
        composeRule.onNodeWithContentDescription("Favourite").assertIsOff()
    }

    private fun showList() {
        val viewModel = CinemasViewModel(repository)
        val nextVisit = NextVisitViewModel(repository, repository, Clock.fixed(SampleProgram.FETCHED_AT, ZoneOffset.UTC), ticks = flowOf(Unit))
        composeRule.setContent {
            CinexTheme {
                CinemasEntry(
                    viewModel, nextVisit, onOpenCinema = {}, onAddVisit = {}, onOpenVisit = {}, onShowTicket = {},
                    message = null, onMessageShown = {},
                )
            }
        }
    }

    @Test
    fun favouritesChipShowsEmptyStateWithoutFavourites() {
        showList()
        composeRule.onNodeWithText("Score").assertIsSelected()
        composeRule.onNodeWithText("Favourites").assertIsNotSelected().performClick()
        composeRule.onNodeWithText("Favourites").assertIsSelected()
        composeRule.onNodeWithText("Score").assertIsNotSelected()
        composeRule.onNodeWithText(NO_FAVOURITES_TEXT).assertExists()
        composeRule.onNodeWithText("DELPHI LUX").assertDoesNotExist()
        // The header still counts every venue.
        composeRule.onNodeWithText("13 venues", substring = true).assertExists()

        composeRule.onNodeWithText("Name").performClick()
        composeRule.onNodeWithText(NO_FAVOURITES_TEXT).assertDoesNotExist()
        composeRule.onNodeWithText("BABYLON KREUZBERG").assertExists()
    }

    @Test
    fun favouriteCinemasCarryAnAnnouncedMarkerAndFillTheFavouritesChip() {
        runBlocking { repository.setFavourite(SampleData.DELPHI_LUX, true) }
        showList()
        // The ★ marker's description is merged into the card, which is one button.
        composeRule.onNode(hasText("DELPHI LUX") and hasContentDescription("Favourite"), useUnmergedTree = false)
            .assertExists()
        composeRule.onNode(hasText("BABYLON KREUZBERG") and hasContentDescription("Favourite")).assertDoesNotExist()

        composeRule.onNodeWithText("Favourites").performClick()
        composeRule.onNodeWithText("DELPHI LUX").assertExists()
        composeRule.onNodeWithText("BABYLON KREUZBERG").assertDoesNotExist()
        composeRule.onNodeWithText(NO_FAVOURITES_TEXT).assertDoesNotExist()
    }
}
