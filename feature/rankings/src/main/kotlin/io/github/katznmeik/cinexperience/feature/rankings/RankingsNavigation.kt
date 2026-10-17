package io.github.katznmeik.cinexperience.feature.rankings

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.katznmeik.cinexperience.core.data.repository.RankingsRepository
import io.github.katznmeik.cinexperience.core.ui.component.ScreenBackground
import io.github.katznmeik.cinexperience.core.ui.navigation.dropUnlessResumedWith
import io.github.katznmeik.cinexperience.core.ui.navigation.resultMessage
import kotlinx.serialization.Serializable

/** Rankings tab (spec §6.6). */
@Serializable
data object RankingsRoute

/**
 * A message left by a screen opened from here when it closes (e.g. a cinema detail
 * whose cinema was deleted, `navigateUpWithResultMessage`) is shown in a snackbar and
 * then cleared.
 */
fun NavGraphBuilder.rankingsScreen(
    rankingsRepository: RankingsRepository,
    onOpenHall: (Long) -> Unit,
    onOpenCinema: (Long) -> Unit,
) {
    composable<RankingsRoute> { entry ->
        val (message, onMessageShown) = entry.resultMessage()
        ScreenBackground {
            RankingsEntry(
                viewModel = viewModel(factory = RankingsViewModel.factory(rankingsRepository)),
                onOpenHall = dropUnlessResumedWith(onOpenHall),
                onOpenCinema = dropUnlessResumedWith(onOpenCinema),
                message = message,
                onMessageShown = onMessageShown,
            )
        }
    }
}
