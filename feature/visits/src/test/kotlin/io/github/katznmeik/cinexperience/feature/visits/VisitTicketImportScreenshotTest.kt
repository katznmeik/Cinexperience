package io.github.katznmeik.cinexperience.feature.visits

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.SavedStateHandle
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.TicketDraft
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import io.github.katznmeik.cinexperience.core.model.VisitPrefillArgs
import io.github.katznmeik.cinexperience.core.seatmapui.BOOKING_LEGEND_DESCRIPTION
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeVisitPhotoStore
import io.github.katznmeik.cinexperience.core.testing.MainDispatcherRule
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import io.github.katznmeik.cinexperience.feature.visits.edit.DRAFT_NO_SEATS
import io.github.katznmeik.cinexperience.feature.visits.edit.DRAFT_QR_KEPT
import io.github.katznmeik.cinexperience.feature.visits.edit.IMPORT_NOTES_TITLE
import io.github.katznmeik.cinexperience.feature.visits.edit.SeatPickerState
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditActions
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditMode
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditScreen
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditUiState
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Screenshots of Add visit opened by the ticket import (spec §6.10) with invented
 * data: one seat, two seats with the booking marked in the seat picker, and a ticket
 * whose hall the app doesn't have (the notes). PNGs land in
 * feature/visits/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h2000dp-port-night-xxhdpi")
class VisitTicketImportScreenshotTest {

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent { ScreenshotSurface { content() } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun oneSeat() {
        val state = VisitImportStates.addWithTicket(seats = listOf(TicketSeat("7", "10")))
        capture("add_visit_ticket_one_seat") { VisitEditScreen(state, photo = null, actions = object : VisitEditActions {}) }
        composeRule.onNodeWithText("Show ticket").assertDoesNotExist()
        composeRule.onNodeWithText("Remove ticket").assertExists()
        composeRule.onNodeWithText(DRAFT_QR_KEPT).assertExists()
    }

    /** The seats couldn't be read: the ticket is saved without them (spec §4.7). */
    @Test
    fun seatsNotRead() {
        val state = VisitImportStates.addWithTicket(seats = emptyList())
        capture("add_visit_ticket_no_seats") { VisitEditScreen(state, photo = null, actions = object : VisitEditActions {}) }
        composeRule.onNodeWithText(DRAFT_NO_SEATS).assertExists()
        composeRule.onNodeWithText("Seats of the booking").assertDoesNotExist()
        composeRule.onNodeWithText("Seat of the booking").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w412dp-h1400dp-port-night-xxhdpi")
    fun severalSeatsInThePicker() {
        val state = VisitImportStates.picker()
        capture("add_visit_ticket_picker") { VisitEditScreen(state, photo = null, actions = object : VisitEditActions {}) }
        composeRule.onNodeWithContentDescription(BOOKING_LEGEND_DESCRIPTION).assertExists()
        composeRule.onNodeWithContentDescription("Row 7, Seat 11, in this booking").assertExists()
        composeRule.onNodeWithContentDescription("Seats in this booking: Row 7, Seat 10; Row 7, Seat 11.", substring = true).assertExists()
    }

    @Test
    fun aHallTheAppDoesntHave() {
        val state = VisitImportStates.addWithNotes()
        capture("add_visit_ticket_notes") { VisitEditScreen(state, photo = null, actions = object : VisitEditActions {}) }
        composeRule.onNodeWithText(IMPORT_NOTES_TITLE).assertExists()
        composeRule.onNodeWithText("Hall on the ticket: \u2068Saal 9\u2069 (not in the app)").assertExists()
    }
}

/** The states of Add visit with an imported ticket, for the screenshots and accessibility tests. */
internal object VisitImportStates {
    private val clock: Clock = Clock.fixed(SampleProgram.FETCHED_AT, ZoneOffset.UTC)
    private val saturday = LocalDate.of(2026, 10, 17)

    private fun viewModel(hallId: Long?, prefill: VisitPrefillArgs): VisitEditViewModel {
        val repository = FakeCinexRepository(SampleTickets.store(visits = emptyList(), tickets = emptyList()))
        return VisitEditViewModel(
            VisitEditMode.Add(hallId, prefill), repository, repository, FakeVisitPhotoStore(), SavedStateHandle(),
            ticketRepository = repository, clock = clock, today = { LocalDate.of(2026, 10, 15) },
        )
    }

    private fun prefill(seats: List<TicketSeat>, notes: List<String> = emptyList(), seatId: Boolean = true) = VisitPrefillArgs(
        cinemaId = SampleData.DELPHI_LUX,
        date = saturday,
        filmTitle = SampleTickets.FILM,
        format = ScreeningFormat.OMU,
        startTime = SampleTickets.START,
        seatId = if (seatId) SampleTickets.row7Seat10 else null,
        ticket = TicketDraft(BookingNumber("TEST123"), seats, SampleTickets.qrModules()),
        notes = notes,
    )

    fun addWithTicket(seats: List<TicketSeat> = SampleTickets.seats): VisitEditUiState =
        viewModel(SampleTickets.HALL, prefill(seats)).uiState.value

    /** The seat picker of a two-seat booking, the second seat picked (both marked). */
    fun picker(): VisitEditUiState {
        val vm = viewModel(SampleTickets.HALL, prefill(SampleTickets.seats))
        vm.openSeatPicker()
        vm.tapPickerSeat(SampleTickets.saal4Layout.rows[6].seatIds[10])
        return vm.uiState.value.also { check(it.seatPicker == SeatPickerState(SampleTickets.saal4Layout.rows[6].seatIds[10])) }
    }

    /** A ticket for a hall the app doesn't have: the cinema is set, the hall and seat stay empty, the notes say why. */
    fun addWithNotes(): VisitEditUiState = viewModel(
        hallId = null,
        prefill(SampleTickets.seats, notes = NOTES, seatId = false),
    ).uiState.value

    /** The import's notes for that ticket (as TicketMatching writes them). */
    val NOTES = listOf(
        "Hall on the ticket: \u2068Saal 9\u2069 (not in the app)",
        "Seats on the ticket: \u2068Row 7 · Seat 10\u2069, \u2068Row 7 · Seat 11\u2069 (not selected: choose the hall first)",
    )
}
