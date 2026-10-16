package io.github.katznmeik.cinexperience.feature.backup

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.data.backup.BackupRepository
import io.github.katznmeik.cinexperience.core.data.backup.ImportPreview
import io.github.katznmeik.cinexperience.core.data.backup.ImportSource
import io.github.katznmeik.cinexperience.core.data.backup.SafetyCopy
import io.github.katznmeik.cinexperience.core.program.ProgramTime
import io.github.katznmeik.cinexperience.core.ui.component.CinexScreen
import io.github.katznmeik.cinexperience.core.ui.component.MessageSnackbarHost
import io.github.katznmeik.cinexperience.core.ui.component.RowDivider
import io.github.katznmeik.cinexperience.core.ui.component.RowGroup
import io.github.katznmeik.cinexperience.core.ui.component.SectionHeader
import io.github.katznmeik.cinexperience.core.ui.component.rememberMessageSnackbarState
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.text.countText
import io.github.katznmeik.cinexperience.core.ui.text.dateTimeText
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** Backup callbacks of the Settings screen, implemented by [SettingsViewModel] and the file pickers. */
internal interface BackupActions {
    fun onExport() {}
    fun onImport() {}
    fun onRestoreSafetyCopy() {}
    fun onSafetyCopyChosen(copyId: Long) {}
    fun onCancelSafetyCopyChoice() {}
    fun onConfirmImport() {}
    fun onCancelImport() {}
    fun onMessageShown() {}

    companion object {
        val None = object : BackupActions {}
    }
}

/** Program callbacks of the Settings screen (spec §6.7), implemented by [SettingsViewModel]. */
internal interface ProgramSettingsActions {
    fun onDownloadsChanged(enabled: Boolean) {}
    fun onDeleteProgram() {}
    fun onConfirmDelete() {}
    fun onCancelDelete() {}

    companion object {
        val None = object : ProgramSettingsActions {}
    }
}

@Composable
internal fun SettingsEntry(
    viewModel: SettingsViewModel,
    onOpenManage: () -> Unit,
    onOpenAllVisits: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupRepository.MIME_TYPE)) { uri ->
        viewModel.onExportDestinationChosen(uri?.toString())
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onImportSourceChosen(uri?.toString())
    }
    val actions = remember(viewModel) {
        object : BackupActions {
            override fun onExport() = launchPicker(viewModel) { exportPicker.launch(viewModel.exportFileName()) }
            override fun onImport() = launchPicker(viewModel) { importPicker.launch(BackupRepository.IMPORT_MIME_TYPES) }
            override fun onRestoreSafetyCopy() = viewModel.onRestoreSafetyCopy()
            override fun onSafetyCopyChosen(copyId: Long) = viewModel.onSafetyCopyChosen(copyId)
            override fun onCancelSafetyCopyChoice() = viewModel.onCancelSafetyCopyChoice()
            override fun onConfirmImport() = viewModel.onConfirmImport()
            override fun onCancelImport() = viewModel.onCancelImport()
            override fun onMessageShown() = viewModel.onMessageShown()
        }
    }
    val programActions = remember(viewModel) {
        object : ProgramSettingsActions {
            override fun onDownloadsChanged(enabled: Boolean) = viewModel.onProgramDownloadsChanged(enabled)
            override fun onDeleteProgram() = viewModel.onDeleteProgram()
            override fun onConfirmDelete() = viewModel.onConfirmDeleteProgram()
            override fun onCancelDelete() = viewModel.onCancelDeleteProgram()
        }
    }
    SettingsScreen(state, onOpenManage, onOpenAllVisits, actions = actions, programActions = programActions)
}

private fun launchPicker(viewModel: SettingsViewModel, launch: () -> Unit) {
    try {
        launch()
    } catch (e: ActivityNotFoundException) {
        viewModel.onPickerUnavailable()
    }
}

/** Settings (spec §6.7): backup, data management, about. */
@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    onOpenManage: () -> Unit,
    onOpenAllVisits: () -> Unit,
    modifier: Modifier = Modifier,
    actions: BackupActions = BackupActions.None,
    programActions: ProgramSettingsActions = ProgramSettingsActions.None,
) {
    val backup = state.backup
    // While an import replaces the data nothing else may write: Back and the Data rows
    // are blocked here, tab switching in CinexApp.
    val importing = backup.task is BackupTask.Importing
    // An export runs in this screen's ViewModel: Back would clear it and cancel the
    // export without a word, so Back waits for it too (switching tabs keeps it).
    val exporting = backup.task is BackupTask.Exporting
    BackHandler(enabled = importing || exporting) {}
    val snackbarHostState = rememberMessageSnackbarState(backup.message, actions::onMessageShown)
    Box(modifier.fillMaxSize()) {
        CinexScreen(title = "Settings") {
            Section("Backup") { BackupRows(backup, actions) }
            Section("Data") {
                val counts = state.counts
                SettingsRow(
                    icon = CinexIcons.Cinemas,
                    title = "Manage cinemas & halls",
                    subtitle = when {
                        counts != null -> "${countText(counts.cinemas, "cinema")} · ${countText(counts.halls, "hall")} · " +
                            "${countText(counts.layoutsDrawn, "layout")} drawn"
                        state.countsError -> "Couldn't load the counts"
                        else -> null
                    },
                    enabled = !importing,
                    onClick = onOpenManage,
                )
                RowDivider()
                SettingsRow(
                    icon = CinexIcons.List,
                    title = "All visits",
                    subtitle = counts?.let { if (it.visits == 0) "No visits yet" else countText(it.visits, "visit") },
                    enabled = !importing,
                    onClick = onOpenAllVisits,
                )
            }
            Section("Program") { ProgramRows(state.program, enabled = !importing, programActions) }
            Section("About") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "CINEXPERIENCE",
                        style = CinexTheme.textStyles.cardTitle.copy(fontWeight = FontWeight.ExtraBold),
                        color = CinexTheme.colors.text,
                    )
                    // Non-breaking spaces keep "Version 1.0.0" and "Build abc1234" together when the line wraps.
                    Text(
                        "Version ${state.appVersion} · Build ${state.buildId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinexTheme.colors.textMuted,
                    )
                    Text(
                        "Unofficial app, not affiliated with Yorck Kinos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinexTheme.colors.textMuted,
                    )
                }
            }
        }
        MessageSnackbarHost(snackbarHostState)
    }
    backup.preview?.let { ImportPreviewDialog(it, actions) }
    if (backup.choosingSafetyCopy) SafetyCopyChooser(backup.safetyCopies, actions)
    if (state.program.confirmingDelete) {
        AlertDialog(
            onDismissRequest = programActions::onCancelDelete,
            title = { Text("Delete downloaded program?") },
            text = {
                Text(
                    "The program is removed from this phone. It can be downloaded again in the Program tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinexTheme.colors.textMuted,
                )
            },
            confirmButton = { TextButton(onClick = programActions::onConfirmDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = programActions::onCancelDelete) { Text("Cancel") } },
        )
    }
}

/**
 * Settings → Program (spec §6.7): the download switch (off = no network requests),
 * the date of the last download and "Delete downloaded program".
 */
@Composable
private fun ProgramRows(program: ProgramSettingsState, enabled: Boolean, actions: ProgramSettingsActions) {
    val switchEnabled = enabled && program.isLoaded
    val titleColor = if (switchEnabled) CinexTheme.colors.text else CinexTheme.colors.textMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(
                value = program.downloadsOn,
                enabled = switchEnabled,
                role = Role.Switch,
                onValueChange = actions::onDownloadsChanged,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(CinexIcons.Program, contentDescription = null, tint = titleColor, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Download program from yorck.de",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = titleColor,
            )
            Text(
                if (program.downloadsOn) {
                    "Only when you open the Program tab or pull to refresh"
                } else {
                    "Off: the app makes no network requests"
                },
                style = MaterialTheme.typography.bodySmall,
                color = CinexTheme.colors.textMuted,
            )
        }
        // The row is the toggle; the switch only shows its state.
        Switch(
            checked = program.downloadsOn,
            onCheckedChange = null,
            enabled = switchEnabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CinexTheme.colors.onPrimary,
                checkedTrackColor = CinexTheme.colors.text,
                uncheckedThumbColor = CinexTheme.colors.fieldOutline,
                uncheckedTrackColor = CinexTheme.colors.surface,
                uncheckedBorderColor = CinexTheme.colors.fieldOutline,
            ),
        )
    }
    RowDivider()
    SettingsRow(
        icon = CinexIcons.Delete,
        title = "Delete downloaded program",
        subtitle = when {
            program.isDownloading -> "Downloading…"
            program.lastDownload != null -> "Last download: ${dateTimeText(program.lastDownload, ProgramTime.ZONE)}"
            program.isLoaded -> "No program downloaded"
            else -> null
        },
        enabled = enabled && program.hasProgram,
        onClick = actions::onDeleteProgram,
        // Opens a confirmation, not a page: no chevron.
        trailing = {},
    )
    program.errorMessage?.let { message ->
        RowDivider()
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = CinexTheme.colors.text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** The safety copies to restore from, newest first; picking one leads to the usual preview. */
@Composable
private fun SafetyCopyChooser(copies: List<SafetyCopy>, actions: BackupActions) {
    AlertDialog(
        onDismissRequest = actions::onCancelSafetyCopyChoice,
        title = { Text("Restore safety copy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Your data as it was before an import, newest first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinexTheme.colors.textMuted,
                )
                RowGroup {
                    copies.forEachIndexed { index, copy ->
                        if (index > 0) RowDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable(role = Role.Button) { actions.onSafetyCopyChosen(copy.id) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    safetyCopyTitle(copy),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = CinexTheme.colors.text,
                                )
                                Text(safetyCopyContents(copy), style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
                            }
                            Icon(
                                CinexIcons.ChevronRight,
                                contentDescription = null,
                                tint = CinexTheme.colors.textMuted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = actions::onCancelSafetyCopyChoice) { Text("Cancel") } },
    )
}

@Composable
private fun BackupRows(backup: BackupUiState, actions: BackupActions) {
    val task = backup.task
    val exporting = task as? BackupTask.Exporting
    val importing = task as? BackupTask.Importing
    val idle = !backup.isBusy

    SettingsRow(
        icon = CinexIcons.Export,
        title = "Export backup",
        subtitle = when {
            exporting != null -> "Exporting… ${percentText(exporting.progress)}"
            backup.lastExport != null -> lastExportText(backup.lastExport)
            else -> "No export yet"
        },
        enabled = idle || exporting != null,
        onClick = if (idle) actions::onExport else null,
        progress = exporting?.let { RowProgress(it.progress) },
    )
    RowDivider()
    val importFromFile = importing?.source == ImportSource.FILE
    SettingsRow(
        icon = CinexIcons.Import,
        title = "Import backup",
        subtitle = when {
            task == BackupTask.CheckingBackup -> "Checking the backup…"
            importing != null && importFromFile -> "Importing… ${percentText(importing.progress)}"
            else -> "Replaces all current data"
        },
        enabled = idle || task == BackupTask.CheckingBackup || importFromFile,
        onClick = if (idle) actions::onImport else null,
        progress = when {
            task == BackupTask.CheckingBackup -> RowProgress(null)
            importing != null && importFromFile -> RowProgress(importing.progress)
            else -> null
        },
    )
    val copies = backup.safetyCopies
    val restoring = importing?.source == ImportSource.SAFETY_COPY
    if (copies.isNotEmpty() || restoring) {
        RowDivider()
        SettingsRow(
            icon = CinexIcons.Undo,
            title = "Restore safety copy",
            subtitle = when {
                restoring -> "Restoring… ${percentText(importing!!.progress)}"
                else -> safetyCopiesSummary(copies)
            },
            enabled = idle || restoring || backup.choosingSafetyCopy,
            onClick = if (idle) actions::onRestoreSafetyCopy else null,
            progress = if (restoring) RowProgress(importing!!.progress) else null,
        )
    }
    RowDivider()
    // The manifest allows Android Auto Backup (spec §8) for the database and preferences.
    SettingsRow(CinexIcons.Cloud, "Android auto backup", "Backs up to your Google account", onClick = null) {
        Text("On", style = MaterialTheme.typography.labelLarge, color = CinexTheme.colors.textMuted)
    }
}

/** Preview of a validated backup (spec §8): what it contains and that it replaces everything. */
@Composable
private fun ImportPreviewDialog(preview: ImportPreview, actions: BackupActions) {
    val fromSafetyCopy = preview.source == ImportSource.SAFETY_COPY
    AlertDialog(
        onDismissRequest = actions::onCancelImport,
        title = { Text(if (fromSafetyCopy) "Restore safety copy?" else "Import backup?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (fromSafetyCopy) {
                        "Saved ${dateTimeText(preview.exportedAt)}, before an import"
                    } else {
                        "Exported ${dateTimeText(preview.exportedAt)} · app version ${preview.appVersion}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinexTheme.colors.textMuted,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    previewCountLines(preview.counts).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text)
                    }
                }
                Text(
                    "This replaces all current data.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = CinexTheme.colors.text,
                )
                Text(
                    "A safety copy of your current data is saved first. You can restore it in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinexTheme.colors.textMuted,
                )
            }
        },
        confirmButton = { TextButton(onClick = actions::onConfirmImport) { Text("Replace") } },
        dismissButton = { TextButton(onClick = actions::onCancelImport) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title)
        RowGroup { content() }
    }
}

/** A progress bar under a row's text; [fraction] `null` = indeterminate. */
private data class RowProgress(val fraction: Float?)

/**
 * Icon, title, muted subtitle and a trailing chevron (or [trailing]). Disabled rows
 * are drawn muted, can't be tapped and are announced as disabled buttons; enabled
 * rows without [onClick] are informational (or show a running task).
 * With [progress], a progress bar is drawn under the subtitle.
 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = {},
    progress: RowProgress? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val titleColor = if (enabled) CinexTheme.colors.text else CinexTheme.colors.textMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(
                when {
                    onClick != null -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    // A button that can't be used now (another task runs): one announcement, "disabled".
                    !enabled -> Modifier.semantics(mergeDescendants = true) {
                        role = Role.Button
                        disabled()
                    }
                    else -> Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = titleColor, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = titleColor,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
            }
            if (progress != null) {
                val barModifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                if (progress.fraction != null) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = barModifier,
                        color = CinexTheme.colors.text,
                        trackColor = CinexTheme.colors.outline,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = barModifier,
                        color = CinexTheme.colors.text,
                        trackColor = CinexTheme.colors.outline,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                    )
                }
            }
        }
        when {
            trailing != null -> trailing()
            enabled && onClick != null -> Icon(
                CinexIcons.ChevronRight,
                contentDescription = null,
                tint = CinexTheme.colors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
