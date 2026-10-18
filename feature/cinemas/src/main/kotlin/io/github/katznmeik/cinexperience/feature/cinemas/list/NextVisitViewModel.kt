package io.github.katznmeik.cinexperience.feature.cinemas.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.repository.TicketRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.ShowingTime
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.UpcomingVisits
import io.github.katznmeik.cinexperience.core.model.VisitListItem
import io.github.katznmeik.cinexperience.core.model.qrModulesExpireAt
import io.github.katznmeik.cinexperience.core.ui.flow.asResult
import io.github.katznmeik.cinexperience.core.ui.flow.minuteTicks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * The Next visit card above the cinema list (spec §6.1): the soonest upcoming visit.
 *
 * @property today today in Berlin, for "Today" / "Tomorrow".
 * @property seatLabel the visit's seat in the hall's current layout; `null` without one.
 * @property canShowTicket the visit has a ticket with a QR code that is still kept ("Show ticket").
 */
data class NextVisit(
    val visitId: Long,
    val filmTitle: String?,
    val date: LocalDate,
    val startTime: LocalTime?,
    val today: LocalDate,
    val cinemaName: String,
    val hallName: String,
    val seatLabel: SeatLabel?,
    val canShowTicket: Boolean,
)

/**
 * Picks the Next visit (spec §6.1): the first of [UpcomingVisits] at [clock]'s now,
 * `null` while there is none. Whether a visit is upcoming changes with time, so it
 * is picked again on every tick (default: every minute). A failure to load leaves
 * the card out (the list shows its own message); a failure to read the ticket
 * leaves out only "Show ticket".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NextVisitViewModel(
    visitRepository: VisitRepository,
    private val ticketRepository: TicketRepository,
    private val clock: Clock,
    ticks: Flow<Unit> = minuteTicks(),
) : ViewModel() {

    /** The picked visit and Berlin's today when it was picked. */
    private data class Pick(val item: VisitListItem, val today: LocalDate)

    val uiState: StateFlow<NextVisit?> =
        combine(visitRepository.observeAllVisits().asResult(), ticks) { visits, _ ->
            val now = clock.instant()
            UpcomingVisits.of(visits.getOrDefault(emptyList()), now).firstOrNull()?.let { Pick(it, ShowingTime.today(now)) }
        }
            .distinctUntilChanged()
            .flatMapLatest { pick ->
                when {
                    pick == null -> flowOf(null)
                    !pick.item.hasTicket -> flowOf(card(pick, ticket = null))
                    else -> ticketRepository.observeTicket(pick.item.visit.id).asResult().map { card(pick, it.getOrNull()) }
                }
            }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun card(pick: Pick, ticket: Ticket?): NextVisit {
        val visit = pick.item.visit
        return NextVisit(
            visitId = visit.id,
            filmTitle = visit.filmTitle,
            date = visit.date,
            startTime = visit.startTime,
            today = pick.today,
            cinemaName = pick.item.cinemaName,
            hallName = pick.item.hallName,
            seatLabel = pick.item.seatLabel,
            canShowTicket = ticket?.qrModules != null && clock.instant().isBefore(visit.qrModulesExpireAt),
        )
    }

    companion object {
        fun factory(visitRepository: VisitRepository, ticketRepository: TicketRepository, clock: Clock): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { NextVisitViewModel(visitRepository, ticketRepository, clock) }
            }
    }
}
