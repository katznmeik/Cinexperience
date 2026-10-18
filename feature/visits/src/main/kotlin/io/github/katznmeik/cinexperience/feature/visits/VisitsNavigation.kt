package io.github.katznmeik.cinexperience.feature.visits

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.katznmeik.cinexperience.core.data.files.VisitPhotoStore
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.TicketRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.LayoutRow
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.ShowingTime
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.TicketDraft
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import io.github.katznmeik.cinexperience.core.model.VisitPrefillArgs
import io.github.katznmeik.cinexperience.core.ui.component.ScreenBackground
import io.github.katznmeik.cinexperience.core.ui.navigation.dropUnlessResumedWith
import io.github.katznmeik.cinexperience.core.ui.text.takeCodePoints
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitDates
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditEntry
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditMode
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditViewModel
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsEntry
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsViewModel
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Add a visit (spec §6.4). [hallId] pre-selects the hall (opened from a hall, or the
 * hall of an imported ticket); `null` lets the user pick cinema and hall (Cinemas tab).
 *
 * The other arguments pre-fill the form, see [VisitPrefillArgs]: from a showing of
 * the program ("Log visit", spec §11.1) or from an imported ticket (§6.10). [date] is
 * an ISO date (`2026-10-15`), [format] a [ScreeningFormat.storageKey], [startTime]
 * "HH:mm", [seatId] the owner's seat in [hallId]'s layout. An imported ticket is
 * [bookingNumber] with its seats ([ticketRows] and [ticketSeats], one entry each per
 * seat, a row without a name as [LayoutRow.NO_NAME]) and [qrModules]
 * ([QrModules.encode]); [ticketNotes] are the import's notes. Unreadable values, and a
 * date outside the years the form's date picker offers, are ignored (a ticket without a
 * valid booking number is left out).
 */
@Serializable
data class AddVisitRoute(
    val hallId: Long? = null,
    val cinemaId: Long? = null,
    val date: String? = null,
    val filmTitle: String? = null,
    val format: String? = null,
    val startTime: String? = null,
    val seatId: Int? = null,
    val bookingNumber: String? = null,
    val ticketRows: List<String>? = null,
    val ticketSeats: List<String>? = null,
    val qrModules: String? = null,
    val ticketNotes: List<String>? = null,
) {
    internal fun prefill(): VisitPrefillArgs? {
        val ticketArgs = listOf(bookingNumber, ticketRows, ticketSeats, qrModules, ticketNotes)
        if (listOf(cinemaId, date, filmTitle, format, startTime, seatId).all { it == null } && ticketArgs.all { it == null }) return null
        return VisitPrefillArgs(
            cinemaId = cinemaId,
            // Only a date the form's date picker offers (VisitDates).
            date = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf(VisitDates::isAllowed),
            filmTitle = filmTitle,
            format = format?.let { key -> ScreeningFormat.entries.firstOrNull { it.storageKey == key } },
            startTime = startTime?.let(::startTimeOrNull),
            seatId = seatId?.takeIf { it > 0 }?.let(::SeatId),
            ticket = ticketDraft(),
            notes = ticketNotes.orEmpty().take(VisitPrefillArgs.MAX_NOTES).map { it.takeCodePoints(VisitPrefillArgs.MAX_NOTE_LENGTH) },
        )
    }

    /** The imported ticket; `null` without a valid booking number. Seats that can't be read are left out. */
    private fun ticketDraft(): TicketDraft? {
        val number = bookingNumber?.let(BookingNumber::ofOrNull) ?: return null
        val rows = ticketRows.orEmpty()
        val seats = ticketSeats.orEmpty()
        val ticketSeats = if (rows.size == seats.size) {
            rows.zip(seats).mapNotNull { (row, seat) -> TicketSeat.ofOrNull(row.takeUnless { it == LayoutRow.NO_NAME }, seat) }
                .distinct()
                .take(Ticket.MAX_SEATS)
        } else {
            emptyList()
        }
        return TicketDraft(number, ticketSeats, qrModules?.let(QrModules::decodeOrNull))
    }

    companion object {
        /** The route that opens Add visit for [hallId] with [prefill] (see the class comment). */
        fun of(hallId: Long?, prefill: VisitPrefillArgs): AddVisitRoute = AddVisitRoute(
            hallId = hallId,
            cinemaId = prefill.cinemaId,
            date = prefill.date?.toString(),
            filmTitle = prefill.filmTitle,
            format = prefill.format?.storageKey,
            startTime = prefill.startTime?.let(::timeArgument),
            seatId = prefill.seatId?.value,
            bookingNumber = prefill.ticket?.bookingNumber?.value,
            ticketRows = prefill.ticket?.seats?.map { it.row ?: LayoutRow.NO_NAME },
            ticketSeats = prefill.ticket?.seats?.map { it.seat },
            qrModules = prefill.ticket?.qrModules?.encode(),
            ticketNotes = prefill.notes.takeIf { it.isNotEmpty() },
        )
    }
}

/** "20:15": a visit's start time is to the minute. */
private fun timeArgument(time: LocalTime): String = "%02d:%02d".format(Locale.ROOT, time.hour, time.minute)

/** [text] ("HH:mm") as a start time; `null` if it isn't one. */
private fun startTimeOrNull(text: String): LocalTime? = try {
    LocalTime.parse(text).takeIf { ShowingTime.isWholeMinute(it) }
} catch (e: DateTimeParseException) {
    null
}

/**
 * Edit (or delete) an existing visit (spec §6.4). [importedBefore]: opened by the
 * ticket import for a ticket this visit has already (spec §6.10); the form says so once,
 * and with [qrCodeNotStored] that the ticket's QR code couldn't be stored again.
 */
@Serializable
data class EditVisitRoute(val visitId: Long, val importedBefore: Boolean = false, val qrCodeNotStored: Boolean = false)

/** Chronological list of all visits (spec §6.8), reached from Settings. */
@Serializable
data object AllVisitsRoute

fun NavController.navigateToAddVisit(hallId: Long? = null) = navigate(AddVisitRoute(hallId))

/** Add visit pre-filled from a showing of the program (spec §11.1). */
fun NavController.navigateToAddVisit(prefill: VisitPrefillArgs) = navigate(AddVisitRoute.of(hallId = null, prefill))

/**
 * Add visit for [hallId] pre-filled with [prefill], e.g. an imported ticket (spec
 * §6.10); [navOptions] e.g. replace the import screen.
 */
fun NavController.navigateToAddVisit(hallId: Long?, prefill: VisitPrefillArgs, navOptions: NavOptionsBuilder.() -> Unit) =
    navigate(AddVisitRoute.of(hallId, prefill), navOptions)

fun NavController.navigateToEditVisit(visitId: Long) = navigate(EditVisitRoute(visitId))

/**
 * The visit that has the imported ticket already, saying "This ticket was imported
 * before" (spec §6.10), and with [qrCodeNotStored] that its QR code couldn't be stored
 * again; [navOptions] e.g. replace the import screen or Add visit.
 */
fun NavController.navigateToImportedVisit(visitId: Long, qrCodeNotStored: Boolean = false, navOptions: NavOptionsBuilder.() -> Unit) =
    navigate(EditVisitRoute(visitId, importedBefore = true, qrCodeNotStored = qrCodeNotStored), navOptions)

fun NavController.navigateToAllVisits() = navigate(AllVisitsRoute)

/**
 * The add and edit forms. They close themselves through [onClose] once saved,
 * discarded or deleted (closing with unsaved changes asks first). [onClose] is
 * called once, from the form's state, so it is not limited to the resumed state.
 *
 * @param ticketRepository the Ticket section of a visit with a ticket (spec §6.4). The
 *   forms read and remove tickets; the only write is Save of a new visit with an
 *   imported ticket (spec §6.10), which stores both together.
 * @param clock "now" for an imported ticket's QR code, which isn't kept for a showing
 *   more than 7 days past.
 * @param onShowTicket opens the visit's ticket ("Show ticket", spec §6.11) on top of the form.
 * @param onOpenImportedVisit replaces Add visit with visit [visitId], which has the
 *   imported ticket already (it was imported meanwhile, spec §6.10).
 */
fun NavGraphBuilder.visitEditScreens(
    cinemaRepository: CinemaRepository,
    visitRepository: VisitRepository,
    ticketRepository: TicketRepository,
    visitPhotoStore: VisitPhotoStore,
    clock: Clock = Clock.systemDefaultZone(),
    onClose: () -> Unit,
    onShowTicket: (visitId: Long) -> Unit,
    onOpenImportedVisit: (visitId: Long) -> Unit = {},
) {
    composable<AddVisitRoute> { entry ->
        val route = entry.toRoute<AddVisitRoute>()
        ScreenBackground {
            VisitEditEntry(
                viewModel = viewModel(
                    factory = VisitEditViewModel.factory(
                        VisitEditMode.Add(route.hallId, route.prefill()), cinemaRepository, visitRepository, ticketRepository, visitPhotoStore,
                        clock,
                    ),
                ),
                photoStore = visitPhotoStore,
                onClose = onClose,
                onShowTicket = dropUnlessResumedWith(onShowTicket),
                onOpenImportedVisit = onOpenImportedVisit,
            )
        }
    }
    composable<EditVisitRoute> { entry ->
        val route = entry.toRoute<EditVisitRoute>()
        ScreenBackground {
            VisitEditEntry(
                viewModel = viewModel(
                    factory = VisitEditViewModel.factory(
                        VisitEditMode.Edit(route.visitId, importedBefore = route.importedBefore, qrCodeNotStored = route.qrCodeNotStored),
                        cinemaRepository, visitRepository, ticketRepository, visitPhotoStore, clock,
                    ),
                ),
                photoStore = visitPhotoStore,
                onClose = onClose,
                onShowTicket = dropUnlessResumedWith(onShowTicket),
            )
        }
    }
}

/** @param clock "now" for the Upcoming section (spec §6.8). */
fun NavGraphBuilder.allVisitsScreen(
    visitRepository: VisitRepository,
    clock: Clock = Clock.systemDefaultZone(),
    onBack: () -> Unit,
    onOpenVisit: (visitId: Long) -> Unit,
) {
    composable<AllVisitsRoute> {
        ScreenBackground {
            AllVisitsEntry(
                viewModel = viewModel(factory = AllVisitsViewModel.factory(visitRepository, clock)),
                onBack = dropUnlessResumed(block = onBack),
                onOpenVisit = dropUnlessResumedWith(onOpenVisit),
            )
        }
    }
}
