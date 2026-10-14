package io.github.katznmeik.cinexperience.feature.rankings

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.katznmeik.cinexperience.core.data.repository.RankingsRepository
import io.github.katznmeik.cinexperience.core.ui.navigation.dropUnlessResumedWith
import kotlinx.serialization.Serializable

/** Rankings tab (spec §6.6). */
@Serializable
data object RankingsRoute

fun NavGraphBuilder.rankingsScreen(
    rankingsRepository: RankingsRepository,
    onOpenHall: (Long) -> Unit,
    onOpenCinema: (Long) -> Unit,
) {
    composable<RankingsRoute> {
        RankingsEntry(
            viewModel = viewModel(factory = RankingsViewModel.factory(rankingsRepository)),
            onOpenHall = dropUnlessResumedWith(onOpenHall),
            onOpenCinema = dropUnlessResumedWith(onOpenCinema),
        )
    }
}
