package io.github.katznmeik.cinexperience.feature.rankings

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.katznmeik.cinexperience.core.data.repository.RankingsRepository
import io.github.katznmeik.cinexperience.core.ui.navigation.dropUnlessResumedWith
import kotlinx.serialization.Serializable

/** Rankings tab (spec §6.6). */
@Serializable
data object RankingsRoute

/**
 * @param resultMessageKey saved-state key under which a screen opened from here
 *   (e.g. a cinema detail whose cinema was deleted) leaves a message; it is shown
 *   in a snackbar and then cleared. `null` = none.
 */
fun NavGraphBuilder.rankingsScreen(
    rankingsRepository: RankingsRepository,
    onOpenHall: (Long) -> Unit,
    onOpenCinema: (Long) -> Unit,
    resultMessageKey: String? = null,
) {
    composable<RankingsRoute> { entry ->
        val handle = entry.savedStateHandle
        val message = resultMessageKey?.let { key ->
            val value by handle.getStateFlow<String?>(key, null).collectAsStateWithLifecycle()
            value
        }
        RankingsEntry(
            viewModel = viewModel(factory = RankingsViewModel.factory(rankingsRepository)),
            onOpenHall = dropUnlessResumedWith(onOpenHall),
            onOpenCinema = dropUnlessResumedWith(onOpenCinema),
            message = message,
            onMessageShown = { resultMessageKey?.let { handle[it] = null } },
        )
    }
}
