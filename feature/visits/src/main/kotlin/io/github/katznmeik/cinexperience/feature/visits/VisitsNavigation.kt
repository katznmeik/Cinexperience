package io.github.katznmeik.cinexperience.feature.visits

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.katznmeik.cinexperience.core.data.files.VisitPhotoStore
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.TicketRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.VisitPrefillArgs
import io.github.katznmeik.cinexperience.core.ui.component.ScreenBackground
import io.github.katznmeik.cinexperience.core.ui.navigation.dropUnlessResumedWith
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditEntry
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditMode
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditViewModel
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsEntry
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsViewModel
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate

/**
 * Add a visit (spec §6.4). [hallId] pre-selects the hall (opened from a hall);
 * `null` lets the user pick cinema and hall (Cinemas tab).
 *
 * The other arguments pre-fill the form when it is opened from a showing of the
 * program ("Log visit", spec §11.1), see [VisitPrefillArgs]: [date] is an ISO date
 * (`2026-10-15`), [format] a [ScreeningFormat.storageKey]; unreadable values are ignored.
 */
@Serializable
data class AddVisitRoute(
    val hallId: Long? = null,
    val cinemaId: Long? = null,
    val date: String? = null,
    val filmTitle: String? = null,
    val format: String? = null,
) {
    internal fun prefill(): VisitPrefillArgs? {
        if (cinemaId == null && date == null && filmTitle == null && format == null) return null
        return VisitPrefillArgs(
            cinemaId = cinemaId,
            date = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            filmTitle = filmTitle,
            format = format?.let { key -> ScreeningFormat.entries.firstOrNull { it.storageKey == key } },
        )
    }
}

/** Edit (or delete) an existing visit (spec §6.4). */
@Serializable
data class EditVisitRoute(val visitId: Long)

/** Chronological list of all visits (spec §6.8), reached from Settings. */
@Serializable
data object AllVisitsRoute

fun NavController.navigateToAddVisit(hallId: Long? = null) = navigate(AddVisitRoute(hallId))

/** Add visit pre-filled from a showing of the program (spec §11.1). */
fun NavController.navigateToAddVisit(prefill: VisitPrefillArgs) = navigate(
    AddVisitRoute(
        cinemaId = prefill.cinemaId,
        date = prefill.date?.toString(),
        filmTitle = prefill.filmTitle,
        format = prefill.format?.storageKey,
    ),
)

fun NavController.navigateToEditVisit(visitId: Long) = navigate(EditVisitRoute(visitId))

fun NavController.navigateToAllVisits() = navigate(AllVisitsRoute)

/**
 * The add and edit forms. They close themselves through [onClose] once saved,
 * discarded or deleted (closing with unsaved changes asks first). [onClose] is
 * called once, from the form's state, so it is not limited to the resumed state.
 *
 * @param ticketRepository the Ticket section of a visit with a ticket (spec §6.4);
 *   the forms only read and remove tickets, Save never writes them.
 * @param onShowTicket opens the visit's ticket ("Show ticket", spec §6.11) on top of the form.
 */
fun NavGraphBuilder.visitEditScreens(
    cinemaRepository: CinemaRepository,
    visitRepository: VisitRepository,
    ticketRepository: TicketRepository,
    visitPhotoStore: VisitPhotoStore,
    onClose: () -> Unit,
    onShowTicket: (visitId: Long) -> Unit,
) {
    composable<AddVisitRoute> { entry ->
        val route = entry.toRoute<AddVisitRoute>()
        ScreenBackground {
            VisitEditEntry(
                viewModel = viewModel(
                    factory = VisitEditViewModel.factory(
                        VisitEditMode.Add(route.hallId, route.prefill()), cinemaRepository, visitRepository, ticketRepository, visitPhotoStore,
                    ),
                ),
                photoStore = visitPhotoStore,
                onClose = onClose,
                onShowTicket = dropUnlessResumedWith(onShowTicket),
            )
        }
    }
    composable<EditVisitRoute> { entry ->
        val visitId = entry.toRoute<EditVisitRoute>().visitId
        ScreenBackground {
            VisitEditEntry(
                viewModel = viewModel(
                    factory = VisitEditViewModel.factory(VisitEditMode.Edit(visitId), cinemaRepository, visitRepository, ticketRepository, visitPhotoStore),
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
