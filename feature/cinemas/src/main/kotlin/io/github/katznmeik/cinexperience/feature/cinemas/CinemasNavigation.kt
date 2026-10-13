package io.github.katznmeik.cinexperience.feature.cinemas

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Cinemas tab (spec §6.1): list of all cinemas. Start destination of the app. */
@Serializable
data object CinemasRoute

/** Cinema detail (spec §6.2). Will take a cinema id once cinemas exist (M1). */
@Serializable
data object CinemaDetailRoute

fun NavController.navigateToCinemaDetail() = navigate(CinemaDetailRoute)

fun NavGraphBuilder.cinemasScreen(
    onOpenCinema: () -> Unit,
    onAddVisit: () -> Unit,
) {
    composable<CinemasRoute> {
        CinemasScreen(
            onOpenCinema = dropUnlessResumed(block = onOpenCinema),
            onAddVisit = dropUnlessResumed(block = onAddVisit),
        )
    }
}

fun NavGraphBuilder.cinemaDetailScreen(
    onBack: () -> Unit,
    onOpenHall: () -> Unit,
) {
    composable<CinemaDetailRoute> {
        CinemaDetailScreen(
            onBack = dropUnlessResumed(block = onBack),
            onOpenHall = dropUnlessResumed(block = onOpenHall),
        )
    }
}
