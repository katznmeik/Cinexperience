package io.github.katznmeik.cinexperience.feature.backup

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import kotlinx.serialization.Serializable

/** Settings tab (spec §6.7): backup, data management and about. */
@Serializable
data object SettingsRoute

/**
 * @param appVersion the app's version name, shown under About.
 */
fun NavGraphBuilder.settingsScreen(
    cinemaRepository: CinemaRepository,
    appVersion: String,
    onOpenManage: () -> Unit,
    onOpenAllVisits: () -> Unit,
) {
    composable<SettingsRoute> {
        SettingsEntry(
            viewModel = viewModel(factory = SettingsViewModel.factory(cinemaRepository, appVersion)),
            onOpenManage = dropUnlessResumed(block = onOpenManage),
            onOpenAllVisits = dropUnlessResumed(block = onOpenAllVisits),
        )
    }
}
