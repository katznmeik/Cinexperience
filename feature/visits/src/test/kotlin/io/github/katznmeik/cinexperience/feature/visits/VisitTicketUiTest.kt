package io.github.katznmeik.cinexperience.feature.visits

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditEntry
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditMode
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime

/** The form's Start time field and Ticket section (spec §6.4), driven through the real ViewModel. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class VisitTicketUiTest {

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val repository = FakeCinexRepository(SampleTickets.store())
    private val photos = FakeVisitPhotoStore()
    private val shownTickets = mutableListOf<Long>()

    private fun show(mode: VisitEditMode = VisitEditMode.Edit(SampleTickets.VISIT)): VisitEditViewModel {
        val vm = VisitEditViewModel(mode, repository, repository, photos, SavedStateHandle(), today = { LocalDate.of(2026, 10, 15) }, ticketRepository = repository)
        composeRule.setContent { CinexTheme { VisitEditEntry(vm, photos, onClose = {}, onShowTicket = { shownTickets += it }) } }
        return vm
    }

    @Test
    fun showTicketOpensTheVisitsTicket() {
        show()
        composeRule.onNodeWithContentDescription("Booking number, T E S T 1 2 3").performScrollTo()
        composeRule.onNodeWithContentDescription("Row 7, Seat 11").assertExists()
        composeRule.onNodeWithText("Show ticket").performScrollTo().performClick()
        assertEquals(listOf(SampleTickets.VISIT), shownTickets)
    }

    @Test
    fun removeTicketAsksThenRemovesIt() {
        show()
        composeRule.onNodeWithText("Remove ticket").performScrollTo().performClick()
        composeRule.onNodeWithText("Remove the ticket?").assertExists()
        composeRule.onNodeWithText("Remove").performClick()
        composeRule.waitForIdle()
        assertTrue(repository.store.value.tickets.isEmpty())
        composeRule.onNodeWithText("Remove ticket").assertDoesNotExist()
        composeRule.onNodeWithText("EDIT VISIT").assertExists()
    }

    @Test
    fun theStartTimeCanBeCleared() {
        val vm = show()
        composeRule.onNodeWithContentDescription("Start time, 20:15").assertExists()
        composeRule.onNodeWithContentDescription("Clear start time").performClick()
        composeRule.onNodeWithContentDescription("Start time, not set").assertExists()
        assertNull(vm.uiState.value.form.startTime)
    }

    @Test
    fun theTimePickerSetsTheStartTime() {
        val vm = show(VisitEditMode.Add(SampleTickets.HALL))
        composeRule.onNodeWithContentDescription("Clear start time").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Start time, not set").performClick()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.waitForIdle()
        assertEquals(LocalTime.of(20, 0), vm.uiState.value.form.startTime)
        composeRule.onNodeWithContentDescription("Start time, 20:00").assertExists()
    }
}
