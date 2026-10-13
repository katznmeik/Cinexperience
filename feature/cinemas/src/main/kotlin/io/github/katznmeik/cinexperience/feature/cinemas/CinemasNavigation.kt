package io.github.katznmeik.cinexperience.feature.cinemas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.ui.navigation.dropUnlessResumedWith
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailEntry
import io.github.katznmeik.cinexperience.feature.cinemas.detail.CinemaDetailViewModel
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasEntry
import io.github.katznmeik.cinexperience.feature.cinemas.list.CinemasViewModel
import io.github.katznmeik.cinexperience.feature.cinemas.manage.ManageCinemasEntry
import io.github.katznmeik.cinexperience.feature.cinemas.manage.ManageCinemasViewModel
import kotlinx.serialization.Serializable

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

/** Saved-state key of a message handed to the screen below a closing cinema detail. */
private const val RESULT_MESSAGE_KEY = "cinemas.resultMessage"

/**
 * Closes a cinema detail whose cinema was just deleted and shows [message] on the
 * screen it returns to (the cinema list or the manage screen).
 */
fun NavController.closeDeletedCinema(message: String) {
    previousBackStackEntry?.savedStateHandle?.set(RESULT_MESSAGE_KEY, message)
    navigateUp()
}

/** A message left for this screen by [closeDeletedCinema], and a way to clear it. */
@Composable
private fun NavBackStackEntry.resultMessage(): Pair<String?, () -> Unit> {
    val handle = savedStateHandle
    val message by handle.getStateFlow<String?>(RESULT_MESSAGE_KEY, null).collectAsStateWithLifecycle()
    return message to { handle[RESULT_MESSAGE_KEY] = null }
}

fun NavGraphBuilder.cinemasScreen(
    cinemaRepository: CinemaRepository,
    onOpenCinema: (Long) -> Unit,
    onAddVisit: () -> Unit,
) {
    composable<CinemasRoute> { entry ->
        val (message, onMessageShown) = entry.resultMessage()
        CinemasEntry(
            viewModel = viewModel(factory = CinemasViewModel.factory(cinemaRepository)),
            onOpenCinema = dropUnlessResumedWith(onOpenCinema),
            onAddVisit = dropUnlessResumed(block = onAddVisit),
            message = message,
            onMessageShown = onMessageShown,
        )
    }
}

fun NavGraphBuilder.cinemaDetailScreen(
    cinemaRepository: CinemaRepository,
    hallRepository: HallRepository,
    onBack: () -> Unit,
    onOpenHall: (Long) -> Unit,
    onCinemaDeleted: (message: String) -> Unit,
) {
    composable<CinemaDetailRoute> { entry ->
        val cinemaId = entry.toRoute<CinemaDetailRoute>().cinemaId
        CinemaDetailEntry(
            viewModel = viewModel(factory = CinemaDetailViewModel.factory(cinemaId, cinemaRepository, hallRepository)),
            onBack = dropUnlessResumed(block = onBack),
            onOpenHall = dropUnlessResumedWith(onOpenHall),
            onCinemaDeleted = dropUnlessResumedWith(onCinemaDeleted),
        )
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
        ManageCinemasEntry(
            viewModel = viewModel(factory = ManageCinemasViewModel.factory(cinemaRepository, hallRepository)),
            onBack = dropUnlessResumed(block = onBack),
            onOpenCinema = dropUnlessResumedWith(onOpenCinema),
            message = message,
            onMessageShown = onMessageShown,
        )
    }
}
