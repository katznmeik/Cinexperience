package io.github.katznmeik.cinexperience.feature.backup

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.katznmeik.cinexperience.core.data.backup.BackupRepository
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.settings.ReminderSettingsStore
import io.github.katznmeik.cinexperience.core.program.ProgramRepository
import io.github.katznmeik.cinexperience.core.ui.component.ScreenBackground
import kotlinx.serialization.Serializable

/** Settings tab (spec §6.7): backup, data management, reminders, program and about. */
@Serializable
data object SettingsRoute

/**
 * @param reminderSettingsStore the reminder settings (spec §6.7, §11.3); the app's
 *   reminder scheduling follows them.
 * @param appVersion the app's version name, shown under About.
 * @param buildId the short commit hash the APK was built from, shown under About.
 * @param onImportTicket imports the ticket PDF the file picker returned (Data → Import
 *   ticket, spec §6.7, §6.10), as a `content:` URI; the picker's read permission lasts
 *   while the app runs (no persistable permission is taken).
 * @param onRunsBackupImportChanged whether this screen runs a backup import now: the
 *   app covers every other screen meanwhile (also in another window of the app), not this one.
 */
fun NavGraphBuilder.settingsScreen(
    cinemaRepository: CinemaRepository,
    backupRepository: BackupRepository,
    programRepository: ProgramRepository,
    reminderSettingsStore: ReminderSettingsStore,
    appVersion: String,
    buildId: String,
    onOpenManage: () -> Unit,
    onOpenAllVisits: () -> Unit,
    onImportTicket: (uri: String) -> Unit = {},
    onRunsBackupImportChanged: (Boolean) -> Unit = {},
) {
    composable<SettingsRoute> {
        ScreenBackground {
            SettingsEntry(
                viewModel = viewModel(factory = SettingsViewModel.factory(cinemaRepository, backupRepository, programRepository, appVersion, buildId)),
                reminderViewModel = viewModel(factory = ReminderSettingsViewModel.factory(reminderSettingsStore, backupRepository.isImporting)),
                onOpenManage = dropUnlessResumed(block = onOpenManage),
                onOpenAllVisits = dropUnlessResumed(block = onOpenAllVisits),
                // Not dropped unless resumed: the picker's result arrives while the screen is only started.
                onImportTicket = onImportTicket,
                onRunsBackupImportChanged = onRunsBackupImportChanged,
            )
        }
    }
}
