package io.github.katznmeik.cinexperience.feature.tickets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.data.repository.TicketRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.HallDetail
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.ShowingTime
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.qrModulesExpireAt
import io.github.katznmeik.cinexperience.core.ui.flow.minuteTicks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** What the ticket screen shows (spec §6.11). */
sealed interface TicketContent {
    data object Loading : TicketContent

    /** The visit or its ticket doesn't exist (any more). */
    data object NotFound : TicketContent

    data class Error(val message: String) : TicketContent

    data class Shown(val ticket: TicketDetails) : TicketContent
}

/** Why the screen shows the booking number without a QR code. */
enum class MissingQrCode {
    /** The showing is more than 7 days past (spec §4.7), also before the startup removal ran. */
    REMOVED_AFTER_SHOWING,

    /** No QR code is stored, though it would still be kept: e.g. the ticket came back with a backup (§8). */
    NOT_STORED,
}

/** One seat of the booking; [isOwn] = the visit's own seat (spec §4.7). */
data class TicketSeatLine(val label: SeatLabel, val isOwn: Boolean)

/**
 * The ticket of a visit as the screen shows it.
 *
 * @property qrModules the QR code to draw; `null` when there is none to show ([missingQrCode] says why).
 * @property today today in Berlin, for "Today" / "Tomorrow".
 * @property cinemaName `null` only if the visit's hall can't be read.
 */
data class TicketDetails(
    val filmTitle: String?,
    val date: LocalDate,
    val startTime: LocalTime?,
    val today: LocalDate,
    val cinemaName: String?,
    val hallName: String?,
    val seats: List<TicketSeatLine>,
    val bookingNumber: BookingNumber,
    val qrModules: QrModules?,
    val missingQrCode: MissingQrCode?,
)

/**
 * The ticket of visit [visitId] (spec §6.11): its QR code while it is kept (until
 * 7 days after the showing, [Visit.qrModulesExpireAt], checked here with [clock]
 * too, since the startup removal runs only once per process), the showing, the
 * seats and the booking number. It only reads; it works offline.
 *
 * @param ticks re-evaluates "now" (default: every minute), so an expired QR code goes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TicketViewModel(
    visitId: Long,
    visitRepository: VisitRepository,
    hallRepository: HallRepository,
    ticketRepository: TicketRepository,
    private val clock: Clock,
    ticks: Flow<Unit> = minuteTicks(),
) : ViewModel() {

    val uiState: StateFlow<TicketContent> =
        combine(visitRepository.observeVisit(visitId), ticketRepository.observeTicket(visitId)) { visit, ticket -> visit to ticket }
            .distinctUntilChanged()
            .flatMapLatest { (visit, ticket) ->
                if (visit == null || ticket == null) {
                    flowOf(TicketContent.NotFound)
                } else {
                    combine(hallRepository.observeHallDetail(visit.hallId), ticks) { hall, _ ->
                        TicketContent.Shown(details(visit, ticket, hall, clock.instant()))
                    }
                }
            }
            .distinctUntilChanged()
            .catch { emit(TicketContent.Error(LOAD_FAILED)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TicketContent.Loading)

    companion object {
        const val LOAD_FAILED = "Couldn't load the ticket."

        fun factory(
            visitId: Long,
            visitRepository: VisitRepository,
            hallRepository: HallRepository,
            ticketRepository: TicketRepository,
            clock: Clock,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { TicketViewModel(visitId, visitRepository, hallRepository, ticketRepository, clock) }
        }
    }
}

/** [ticket] of [visit] in [hall] at [now]. */
internal fun details(visit: Visit, ticket: Ticket, hall: HallDetail?, now: Instant): TicketDetails {
    val expired = !now.isBefore(visit.qrModulesExpireAt)
    val ownSeat = visit.seatId?.let { hall?.labels?.labelOf(it) }
    return TicketDetails(
        filmTitle = visit.filmTitle,
        date = visit.date,
        startTime = visit.startTime,
        today = ShowingTime.today(now),
        cinemaName = hall?.cinema?.name,
        hallName = hall?.hall?.name,
        seats = ticket.seats.map { seat ->
            TicketSeatLine(seat.label, isOwn = ownSeat != null && ownSeat.row == seat.row && ownSeat.seat == seat.seat)
        },
        bookingNumber = ticket.bookingNumber,
        qrModules = ticket.qrModules.takeUnless { expired },
        missingQrCode = when {
            expired -> MissingQrCode.REMOVED_AFTER_SHOWING
            ticket.qrModules == null -> MissingQrCode.NOT_STORED
            else -> null
        },
    )
}
