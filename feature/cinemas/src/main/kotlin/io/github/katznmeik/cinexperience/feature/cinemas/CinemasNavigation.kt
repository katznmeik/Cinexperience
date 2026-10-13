package io.github.katznmeik.cinexperience.feature.cinemas

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
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

fun NavGraphBuilder.cinemasScreen(
    cinemaRepository: CinemaRepository,
    onOpenCinema: (Long) -> Unit,
    onAddVisit: () -> Unit,
) {
    composable<CinemasRoute> {
        CinemasEntry(
            viewModel = viewModel(factory = CinemasViewModel.factory(cinemaRepository)),
            onOpenCinema = dropUnlessResumedWith(onOpenCinema),
            onAddVisit = dropUnlessResumed(block = onAddVisit),
        )
    }
}

fun NavGraphBuilder.cinemaDetailScreen(
    cinemaRepository: CinemaRepository,
    hallRepository: HallRepository,
    onBack: () -> Unit,
    onOpenHall: (Long) -> Unit,
) {
    composable<CinemaDetailRoute> { entry ->
        val cinemaId = entry.toRoute<CinemaDetailRoute>().cinemaId
        CinemaDetailEntry(
            viewModel = viewModel(factory = CinemaDetailViewModel.factory(cinemaId, cinemaRepository, hallRepository)),
            onBack = dropUnlessResumed(block = onBack),
            onOpenHall = dropUnlessResumedWith(onOpenHall),
        )
    }
}

fun NavGraphBuilder.manageCinemasScreen(
    cinemaRepository: CinemaRepository,
    hallRepository: HallRepository,
    onBack: () -> Unit,
    onOpenCinema: (Long) -> Unit,
) {
    composable<ManageCinemasRoute> {
        ManageCinemasEntry(
            viewModel = viewModel(factory = ManageCinemasViewModel.factory(cinemaRepository, hallRepository)),
            onBack = dropUnlessResumed(block = onBack),
            onOpenCinema = dropUnlessResumedWith(onOpenCinema),
        )
    }
}
