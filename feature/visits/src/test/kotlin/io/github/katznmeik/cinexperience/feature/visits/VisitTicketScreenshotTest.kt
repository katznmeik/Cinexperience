package io.github.katznmeik.cinexperience.feature.visits

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.SavedStateHandle
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditActions
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditMode
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditScreen
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditUiState
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditViewModel
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsScreen
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsUiState
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Screenshots of the v1.4 parts of the visit screens with invented data: the form
 * of a visit with a start time and a ticket (whole form, tall), and All visits with
 * the Upcoming section. PNGs land in feature/visits/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2000dp-port-night-xxhdpi")
class VisitTicketScreenshotTest {

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            CinexTheme {
                Box(Modifier.fillMaxSize().background(CinexTheme.colors.background)) { content() }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun editVisitWithTicket() {
        val state = VisitTicketStates.editWithTicket()
        check(state.ticket != null && state.form.startTime != null)
        capture("edit_visit_ticket_full") { VisitEditScreen(state, photo = null, actions = object : VisitEditActions {}) }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-port-night-xxhdpi")
    fun allVisitsWithUpcoming() {
        capture("all_visits_upcoming") { AllVisitsScreen(VisitTicketStates.allVisitsWithUpcoming(), onBack = {}, onOpenVisit = {}) }
    }
}

/** The states the v1.4 visit screenshots and accessibility tests show. */
internal object VisitTicketStates {
    /** The longest booking number the app accepts (16 characters, invented). */
    val LONG_BOOKING_NUMBER = BookingNumber("ABCD1234EFGH5678")

    fun editWithTicket(bookingNumber: BookingNumber = SampleTickets.BOOKING_NUMBER): VisitEditUiState {
        val store = UpcomingVisitFixtures.store().let { s -> s.copy(tickets = s.tickets.map { it.copy(bookingNumber = bookingNumber) }) }
        val repository = FakeCinexRepository(store)
        val vm = VisitEditViewModel(
            VisitEditMode.Edit(SampleTickets.VISIT), repository, repository, FakeVisitPhotoStore(), SavedStateHandle(),
            today = { LocalDate.of(2026, 10, 15) }, ticketRepository = repository,
        )
        return vm.uiState.value
    }

    fun allVisitsWithUpcoming(): AllVisitsUiState {
        val vm = AllVisitsViewModel(FakeCinexRepository(UpcomingVisitFixtures.store()), UpcomingVisitFixtures.clock, flowOf(Unit))
        return runBlocking { vm.uiState.first { !it.isLoading } }
    }
}
