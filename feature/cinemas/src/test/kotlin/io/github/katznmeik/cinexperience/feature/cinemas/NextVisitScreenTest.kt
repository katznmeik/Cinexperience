package io.github.katznmeik.cinexperience.feature.cinemas

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemaSort
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasScreen
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasUiState
import io.github.katznmeik.cinexperience.feature.cinemas.list.NextVisit
import io.github.katznmeik.cinexperience.feature.cinemas.list.NextVisitViewModel
import io.github.katznmeik.cinexperience.feature.cinemas.list.sortedFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The Next visit card above the cinema list (spec §6.1): screenshots, and its two actions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class NextVisitScreenTest {

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun show(store: FakeStore, now: Instant = SampleProgram.FETCHED_AT) {
        val state = NextVisitScreenStates.list(store)
        val next = NextVisitScreenStates.nextVisit(store, now)
        composeRule.setContent {
            ScreenshotSurface {
                CinemasScreen(
                    state, onSortChange = {}, onOpenCinema = {}, onAddVisit = {},
                    nextVisit = next,
                    onOpenVisit = { opened += "visit $it" },
                    onShowTicket = { opened += "ticket $it" },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun capture(name: String) = composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    @Test
    fun cardWithShowTicket() {
        show(SampleTickets.store())
        capture("cinemas_next_visit")
        composeRule.onNodeWithText("Show ticket").performClick()
        composeRule.onNodeWithContentDescription("Next visit:", substring = true).performClick()
        assertEquals(listOf("ticket ${SampleTickets.VISIT}", "visit ${SampleTickets.VISIT}"), opened)
    }

    @Test
    fun cardWithoutTicketOrStartTime() {
        val visit = SampleTickets.visit(SampleTickets.dateAfter(SampleProgram.FETCHED_AT, 2), startTime = null).copy(seatId = null)
        show(SampleTickets.store(visits = listOf(visit), tickets = emptyList()))
        capture("cinemas_next_visit_no_ticket")
        composeRule.onNodeWithText("Show ticket").assertDoesNotExist()
        // The card is read as one: without a start time only the day, without a seat no seat.
        composeRule.onNodeWithContentDescription("Next visit: \u2068${SampleTickets.FILM}\u2069, Sat 17 Oct, delphi LUX · Saal 4").assertExists()
    }

    /** Ten minutes after the start (spec §6.1): "Started 20:15", "Show ticket" still there. */
    @Test
    fun cardOfAStartedShowing() {
        val saturdayEvening = Instant.parse("2026-10-17T18:25:00Z") // 20:25 in Berlin
        show(SampleTickets.store(), now = saturdayEvening)
        capture("cinemas_next_visit_started")
        composeRule.onNodeWithText("Show ticket").assertExists()
        composeRule.onNodeWithContentDescription("Started 20:15", substring = true).assertExists()
    }
}

/** The cinema list with the Next visit card, as the app shows it at [SampleProgram.FETCHED_AT] (or the given time). */
internal object NextVisitScreenStates {
    fun list(store: FakeStore): CinemasUiState {
        val cinemas = runBlocking { FakeCinexRepository(store).observeCinemas().first() }
        return CinemasUiState(isLoading = false, sort = CinemaSort.SCORE, cinemas = cinemas.sortedFor(CinemaSort.SCORE))
    }

    fun nextVisit(store: FakeStore, now: Instant = SampleProgram.FETCHED_AT): NextVisit? {
        val repository = FakeCinexRepository(store)
        val vm = NextVisitViewModel(repository, repository, Clock.fixed(now, ZoneOffset.UTC), flowOf(Unit))
        return runBlocking { vm.uiState.first { it != null } }
    }
}
