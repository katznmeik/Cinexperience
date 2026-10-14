package io.github.katznmeik.cinexperience.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.backup.BackupException
import io.github.katznmeik.cinexperience.core.data.backup.BackupRepository
import io.github.katznmeik.cinexperience.core.data.backup.ImportPreview
import io.github.katznmeik.cinexperience.core.data.backup.ImportSource
import io.github.katznmeik.cinexperience.core.data.backup.LastExport
import io.github.katznmeik.cinexperience.core.data.backup.SafetyCopy
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Counts shown in Settings → Data (spec §6.7). */
data class DataCounts(
    val cinemas: Int,
    val halls: Int,
    val layoutsDrawn: Int,
    val visits: Int,
) {
    companion object {
        fun of(cinemas: List<CinemaDetail>) = DataCounts(
            cinemas = cinemas.size,
            halls = cinemas.sumOf { it.halls.size },
            layoutsDrawn = cinemas.sumOf { c -> c.halls.count { it.hall.isLayoutDrawn } },
            visits = cinemas.sumOf { it.scores.visitCount },
        )
    }
}

/** A running backup operation. */
sealed interface BackupTask {
    /** @property progress 0..1 */
    data class Exporting(val progress: Float) : BackupTask

    /** Reading and validating a picked backup (or the safety copy). */
    data object CheckingBackup : BackupTask

    /** @property progress 0..1 */
    data class Importing(val progress: Float, val source: ImportSource) : BackupTask
}

/**
 * The Backup section of Settings (spec §6.7, §8).
 *
 * @property safetyCopies the pre-import safety copies, newest first.
 * @property choosingSafetyCopy the list of safety copies to restore from is open.
 * @property preview a validated backup waiting for the user's Cancel / Replace.
 * @property message one-off result or error message for a snackbar.
 */
data class BackupUiState(
    val lastExport: LastExport? = null,
    val safetyCopies: List<SafetyCopy> = emptyList(),
    val choosingSafetyCopy: Boolean = false,
    val task: BackupTask? = null,
    val preview: ImportPreview? = null,
    val message: String? = null,
) {
    /** While true, no other backup action can start. */
    val isBusy: Boolean get() = task != null || preview != null || choosingSafetyCopy
}

data class SettingsUiState(
    val appVersion: String,
    /** `null` while loading or if the counts couldn't be loaded. */
    val counts: DataCounts? = null,
    val countsError: Boolean = false,
    val backup: BackupUiState = BackupUiState(),
)

/** Settings tab (spec §6.7): data counts, backup export / import and the safety copy. */
class SettingsViewModel(
    cinemaRepository: CinemaRepository,
    private val backupRepository: BackupRepository,
    private val appVersion: String,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    /** Task, preview and message; the rest of the state comes from the repositories. */
    private val transient = MutableStateFlow(BackupUiState())

    private val counts: Flow<Result<DataCounts>> = cinemaRepository.observeCinemaDetails()
        .map { Result.success(DataCounts.of(it)) }
        .catch { emit(Result.failure(it)) }

    private val lastExport: Flow<LastExport?> = backupRepository.lastExport.orNullOnError()
    private val safetyCopies: Flow<List<SafetyCopy>> = backupRepository.safetyCopies.catch { emit(emptyList()) }

    val uiState: StateFlow<SettingsUiState> =
        combine(counts.onStart<Result<DataCounts>?> { emit(null) }, lastExport, safetyCopies, transient) { counts, last, copies, backup ->
            SettingsUiState(
                appVersion = appVersion,
                counts = counts?.getOrNull(),
                countsError = counts?.isFailure == true,
                backup = backup.copy(lastExport = last, safetyCopies = copies),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(appVersion))

    /** Default name for the export file picker, e.g. `cinexperience-backup-2026-10-03.zip`. */
    fun exportFileName(): String = BackupRepository.suggestedFileName(today())

    /** The user picked where to save the export; `null` = cancelled. */
    fun onExportDestinationChosen(uri: String?) {
        if (uri == null || !start(BackupTask.Exporting(0f))) return
        viewModelScope.launch {
            val message = try {
                val counts = backupRepository.export(uri) { progress ->
                    transient.update { it.copy(task = BackupTask.Exporting(progress)) }
                }
                "Backup exported · ${countsSummary(counts)}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                e.message
            } catch (e: Throwable) { // anything unexpected, even an Error, becomes a message
                "Couldn't export the backup."
            }
            transient.update { it.copy(task = null, message = message) }
        }
    }

    /** The user picked a backup to import; `null` = cancelled. */
    fun onImportSourceChosen(uri: String?) {
        if (uri != null) prepare { backupRepository.prepareImport(uri) }
    }

    /** Settings → Restore safety copy: opens the list of copies to choose from. */
    fun onRestoreSafetyCopy() {
        while (true) {
            val current = uiState.value.backup
            val local = transient.value
            if (local.isBusy || current.safetyCopies.isEmpty()) return
            if (transient.compareAndSet(local, local.copy(choosingSafetyCopy = true, message = null))) return
        }
    }

    /** A copy was picked in the list: read it and show the usual preview. */
    fun onSafetyCopyChosen(copyId: Long) {
        if (!transient.value.choosingSafetyCopy) return
        transient.update { it.copy(choosingSafetyCopy = false) }
        prepare { backupRepository.prepareSafetyCopyRestore(copyId) }
    }

    /** The list of copies was closed without a choice. */
    fun onCancelSafetyCopyChoice() {
        transient.update { it.copy(choosingSafetyCopy = false) }
    }

    private fun prepare(read: suspend () -> ImportPreview) {
        if (!start(BackupTask.CheckingBackup)) return
        viewModelScope.launch {
            try {
                val preview = read()
                transient.update { it.copy(task = null, preview = preview) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val message = (e as? BackupException)?.message ?: "Couldn't read the backup."
                transient.update { it.copy(task = null, message = message) }
            }
        }
    }

    /** "Replace" in the preview: replaces all data with the previewed backup. */
    fun onConfirmImport() {
        val preview = transient.value.preview ?: return
        if (transient.value.task != null) return
        transient.update { it.copy(preview = null, task = BackupTask.Importing(0f, preview.source)) }
        viewModelScope.launch {
            val message = try {
                val counts = backupRepository.commitImport(preview) { progress ->
                    transient.update { it.copy(task = BackupTask.Importing(progress, preview.source)) }
                }
                val done = if (preview.source == ImportSource.SAFETY_COPY) "Safety copy restored" else "Backup imported"
                "$done · ${countsSummary(counts)}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                e.message
            } catch (e: Throwable) {
                // The repository reports every failure before the commit as a BackupException,
                // so whether the data changed is unknown here: don't claim either.
                "The import stopped with an unexpected error. Please check your data."
            }
            transient.update { it.copy(task = null, message = message) }
        }
    }

    /** "Cancel" in the preview (or dismissing it): nothing changes. */
    fun onCancelImport() {
        val preview = transient.value.preview ?: return
        transient.update { it.copy(preview = null) }
        viewModelScope.launch {
            try {
                backupRepository.discardImport(preview)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Only temporary files are left; the next import clears them.
            }
        }
    }

    /** No app can handle the file picker request. */
    fun onPickerUnavailable() {
        transient.update { it.copy(message = "No file picker is available on this device.") }
    }

    /** The snackbar showed [BackupUiState.message]. */
    fun onMessageShown() {
        transient.update { it.copy(message = null) }
    }

    /** Starts [task] unless another backup operation is running or a preview is open. */
    private fun start(task: BackupTask): Boolean {
        while (true) {
            val current = transient.value
            if (current.isBusy) return false
            if (transient.compareAndSet(current, current.copy(task = task, message = null))) return true
        }
    }

    companion object {
        fun factory(
            cinemaRepository: CinemaRepository,
            backupRepository: BackupRepository,
            appVersion: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(cinemaRepository, backupRepository, appVersion) }
        }
    }
}

private fun <T> Flow<T?>.orNullOnError(): Flow<T?> = catch { emit(null) }
