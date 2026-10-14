package io.github.katznmeik.cinexperience.feature.visits

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.visits.edit.SEAT_MISSING_HINT
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditEntry
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditMode
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** The key flow of the visit form: film, seat from the picker, a rating, save. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class VisitFormUiTest {

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Test
    fun logAVisitWithSeatAndRating() {
        val repository = FakeCinexRepository(SampleData.storeWithSplitHall())
        val photos = FakeVisitPhotoStore()
        val vm = VisitEditViewModel(VisitEditMode.Add(SampleData.DELPHI_LUX_SAAL_1), repository, repository, photos) { LocalDate.of(2026, 10, 14) }
        var closed = false
        composeRule.setContent {
            CinexTheme { VisitEditEntry(vm, photos, onClose = { closed = true }) }
        }
        composeRule.onNodeWithText("ADD VISIT").assertExists()
        composeRule.onNodeWithContentDescription("Film title").performTextInput("Anora")

        composeRule.onNodeWithText("Pick on map").performClick()
        composeRule.onNodeWithText("YOUR SEAT").assertExists()
        composeRule.onNodeWithContentDescription("Row G").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Row G, Seat 10, centre block").performScrollTo().performClick()
        composeRule.onNodeWithText("Use seat").performClick()
        composeRule.onNodeWithText("ROW G · SEAT 10").assertExists()

        // Tapping the selected star again clears the rating.
        composeRule.onNodeWithContentDescription("Seat rating: 4 stars").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Seat rating: 2 stars").performClick()
        composeRule.onNodeWithContentDescription("Seat rating: 2 stars").performClick()
        composeRule.onNodeWithContentDescription("Seat rating: 5 stars").performClick()

        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()
        assertTrue(closed)
        val saved = repository.store.value.visits.last()
        assertEquals("Anora", saved.filmTitle)
        assertEquals(SampleLayouts.splitHall.rows[6].seatIds[13], saved.seatId)
        assertEquals(Rating(5), saved.seatRating)
    }

    @Test
    fun aSavedSeatMissingFromTheLayoutIsExplained() {
        val visit = Visit(id = 900, hallId = SampleData.DELPHI_LUX_SAAL_1, date = LocalDate.of(2026, 9, 1), seatId = SeatId(9_999))
        val repository = FakeCinexRepository(SampleData.storeWithSplitHall().let { it.copy(visits = it.visits + visit) })
        val photos = FakeVisitPhotoStore()
        val vm = VisitEditViewModel(VisitEditMode.Edit(900), repository, repository, photos)
        composeRule.setContent {
            CinexTheme { VisitEditEntry(vm, photos, onClose = {}) }
        }
        composeRule.onNodeWithText("SEAT NO LONGER IN LAYOUT").assertExists()
        composeRule.onNodeWithText(SEAT_MISSING_HINT).assertExists()
        // Touch targets of at least 48 dp.
        composeRule.onNodeWithText("Remove seat").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("Sound: 3 stars").performScrollTo()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithText("Pick on map").performScrollTo().performClick()
        composeRule.onNodeWithText("Your saved seat is no longer in this hall's layout", substring = true).assertExists()
    }
}
