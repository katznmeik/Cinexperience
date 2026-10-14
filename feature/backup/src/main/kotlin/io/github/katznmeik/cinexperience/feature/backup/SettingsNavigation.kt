package io.github.katznmeik.cinexperience.feature.backup

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.katznmeik.cinexperience.core.data.backup.BackupRepository
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import kotlinx.serialization.Serializable

/** Settings tab (spec §6.7): backup, data management and about. */
@Serializable
data object SettingsRoute

/**
 * @param appVersion the app's version name, shown under About.
 * @param buildId the short commit hash the APK was built from, shown under About.
 */
fun NavGraphBuilder.settingsScreen(
    cinemaRepository: CinemaRepository,
    backupRepository: BackupRepository,
    appVersion: String,
    buildId: String,
    onOpenManage: () -> Unit,
    onOpenAllVisits: () -> Unit,
) {
    composable<SettingsRoute> {
        SettingsEntry(
            viewModel = viewModel(factory = SettingsViewModel.factory(cinemaRepository, backupRepository, appVersion, buildId)),
            onOpenManage = dropUnlessResumed(block = onOpenManage),
            onOpenAllVisits = dropUnlessResumed(block = onOpenAllVisits),
        )
    }
}
