package io.github.katznmeik.cinexperience.feature.cinemas

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.data.repository.TicketRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.program.ProgramRepository
import io.github.katznmeik.cinexperience.core.ui.component.ScreenBackground
import io.github.katznmeik.cinexperience.core.ui.navigation.dropUnlessResumedWith
import io.github.katznmeik.cinexperience.core.ui.navigation.navigateUpWithResultMessage
import io.github.katznmeik.cinexperience.core.ui.navigation.resultMessage
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailEntry
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailViewModel
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasEntry
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasViewModel
import io.github.katznmeik.cinexperience.feature.cinemas.list.NextVisitViewModel
import io.github.katznmeik.cinexperience.feature.cinemas.manage.ManageCinemasEntry
import io.github.katznmeik.cinexperience.feature.cinemas.manage.ManageCinemasViewModel
import kotlinx.serialization.Serializable
import java.time.Clock

/** Cinemas tab (spec §6.1): list of all cinemas. Start destination of the app. */
@Serializable
data object CinemasRoute

/** Cinema detail (spec §6.2). */
@Serializable
data class CinemaDetailRoute(val cinemaId: Long)

/** Manage cinemas & halls (spec §6.7), reached from Settings → Data. */
@Serializable
data object ManageCinemasRoute

fun NavController.navigateToCinemaDetail(cinemaId: Long) = navigate(CinemaDetailRoute(cinemaId))

fun NavController.navigateToManageCinemas() = navigate(ManageCinemasRoute)

/**
 * Closes a cinema detail whose cinema was just deleted and shows [message] on the
 * screen it returns to (the cinema list, the manage screen or the rankings).
 */
fun NavController.closeDeletedCinema(message: String) = navigateUpWithResultMessage(message)

/**
 * @param visitRepository and [ticketRepository]: the Next visit card (spec §6.1).
 * @param clock "now" for the Next visit card (which visits are upcoming).
 * @param onOpenVisit opens a visit's form (the Next visit card).
 * @param onShowTicket opens a visit's ticket (the card's "Show ticket", spec §6.11).
 */
fun NavGraphBuilder.cinemasScreen(
    cinemaRepository: CinemaRepository,
    visitRepository: VisitRepository,
    ticketRepository: TicketRepository,
    clock: Clock = Clock.systemDefaultZone(),
    onOpenCinema: (Long) -> Unit,
    onAddVisit: () -> Unit,
    onOpenVisit: (visitId: Long) -> Unit,
    onShowTicket: (visitId: Long) -> Unit,
) {
    composable<CinemasRoute> { entry ->
        val (message, onMessageShown) = entry.resultMessage()
        ScreenBackground {
            CinemasEntry(
                viewModel = viewModel(factory = CinemasViewModel.factory(cinemaRepository)),
                nextVisitViewModel = viewModel(factory = NextVisitViewModel.factory(visitRepository, ticketRepository, clock)),
                onOpenCinema = dropUnlessResumedWith(onOpenCinema),
                onAddVisit = dropUnlessResumed(block = onAddVisit),
                onOpenVisit = dropUnlessResumedWith(onOpenVisit),
                onShowTicket = dropUnlessResumedWith(onShowTicket),
                message = message,
                onMessageShown = onMessageShown,
            )
        }
    }
}

/**
 * @param programRepository the downloaded program for "Now showing" (spec §6.2); only read here.
 * @param clock "now" for "Now showing".
 * @param onOpenFilm opens the film page of a showing (film kind and slug).
 * @param onShowAllInProgram opens the Program tab filtered to the cinema with this seed key.
 * @param onOpenProgram opens the Program tab as it is (the hints without a program).
 */
fun NavGraphBuilder.cinemaDetailScreen(
    cinemaRepository: CinemaRepository,
    hallRepository: HallRepository,
    programRepository: ProgramRepository,
    clock: Clock = Clock.systemDefaultZone(),
    onBack: () -> Unit,
    onOpenHall: (Long) -> Unit,
    onCinemaDeleted: (message: String) -> Unit,
    onOpenFilm: (FilmKind, String) -> Unit,
    onShowAllInProgram: (cinemaSeedKey: String) -> Unit,
    onOpenProgram: () -> Unit,
) {
    composable<CinemaDetailRoute> { entry ->
        val cinemaId = entry.toRoute<CinemaDetailRoute>().cinemaId
        ScreenBackground {
            val viewModel: CinemaDetailViewModel =
                viewModel(factory = CinemaDetailViewModel.factory(cinemaId, cinemaRepository, hallRepository, programRepository, clock))
            val openFilm = dropUnlessResumedWith<Pair<FilmKind, String>> { (kind, slug) -> onOpenFilm(kind, slug) }
            CinemaDetailEntry(
                viewModel = viewModel,
                onBack = dropUnlessResumed(block = onBack),
                onOpenHall = dropUnlessResumedWith(onOpenHall),
                onCinemaDeleted = dropUnlessResumedWith(onCinemaDeleted),
                onOpenFilm = { kind, slug -> openFilm(kind to slug) },
                onShowAllInProgram = dropUnlessResumed {
                    viewModel.cinemaSeedKey?.let(onShowAllInProgram)
                },
                onOpenProgram = dropUnlessResumed(block = onOpenProgram),
            )
        }
    }
}

fun NavGraphBuilder.manageCinemasScreen(
    cinemaRepository: CinemaRepository,
    hallRepository: HallRepository,
    onBack: () -> Unit,
    onOpenCinema: (Long) -> Unit,
) {
    composable<ManageCinemasRoute> { entry ->
        val (message, onMessageShown) = entry.resultMessage()
        ScreenBackground {
            ManageCinemasEntry(
                viewModel = viewModel(factory = ManageCinemasViewModel.factory(cinemaRepository, hallRepository)),
                onBack = dropUnlessResumed(block = onBack),
                onOpenCinema = dropUnlessResumedWith(onOpenCinema),
                message = message,
                onMessageShown = onMessageShown,
            )
        }
    }
}
