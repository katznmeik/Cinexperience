package io.github.katznmeik.cinexperience.core.data.backup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate

/** What a backup holds; also written to its manifest. */
data class BackupCounts(
    val cinemas: Int,
    val halls: Int,
    val visits: Int,
    /** Seat override rows: manual seat colours and favourite seats. */
    val overrides: Int,
    val photos: Int,
    val referenceImages: Int,
)

/** The last successful export from this device (Settings → Export backup). */
data class LastExport(val exportedAt: Instant, val counts: BackupCounts)

/** An automatic copy of the data taken right before an import replaced it. */
data class SafetyCopy(
    /** Identifies the copy for [BackupRepository.prepareSafetyCopyRestore]; a higher id is newer. */
    val id: Long,
    val createdAt: Instant,
    /** What the copy holds; `null` if its manifest can't be read. */
    val counts: BackupCounts?,
)

/** Where a prepared import comes from. */
enum class ImportSource {
    /** A backup file the user picked. */
    FILE,

    /** One of the automatic safety copies ([BackupRepository.safetyCopies]). */
    SAFETY_COPY,
}

/**
 * A backup that was read and fully validated, ready to replace the current data
 * with [BackupRepository.commitImport]. Nothing has been changed yet.
 */
data class ImportPreview(
    /** Identifies the staged backup; only the latest prepared import can be committed. */
    val id: String,
    val source: ImportSource,
    val exportedAt: Instant,
    val appVersion: String,
    val backupVersion: Int,
    val counts: BackupCounts,
)

/** Why a backup operation failed. */
enum class BackupError {
    /** Not a zip file, or not a Cinexperience backup (wrong or missing manifest). */
    NOT_A_BACKUP,

    /** Written by a newer app version with a backup format this app doesn't know. */
    NEWER_VERSION,

    /** Unsafe or unexpected entries: absolute paths, `..`, duplicates, unknown files. */
    UNSAFE_ARCHIVE,

    /** Too many entries or too much data (zip bomb guard). */
    TOO_LARGE,

    /** The data is damaged or inconsistent (e.g. a visit of a hall that doesn't exist). */
    INVALID_DATA,

    /** A photo or reference image the data refers to is missing from the backup. */
    MISSING_FILE,

    /** The file couldn't be opened or read. */
    READ_FAILED,

    /** The backup couldn't be written, or the data couldn't be replaced. */
    WRITE_FAILED,

    /** The prepared import is gone (e.g. its temporary files were cleared); start again. */
    IMPORT_EXPIRED,

    /** There is no safety copy to restore. */
    NO_SAFETY_COPY,

    /** The data changed while an import or export was running; nothing was replaced or exported. */
    DATA_CHANGED,
}

/** A failed backup operation; [message] is user-facing. */
class BackupException(val error: BackupError, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Backup export and import (spec §8): one zip file with `manifest.json`,
 * `data.json` and the app-private `photos/` and `reference/` files. See
 * `BackupFormat.kt` for the format and `RoomBackupRepository` for the guarantees.
 *
 * Every method throws [BackupException] with a user-facing message on failure
 * (only cancellation and fatal VM errors are passed on); a failed import leaves
 * the current data and files untouched.
 */
interface BackupRepository {
    /**
     * True while [commitImport] replaces the data. The UI blocks everything that
     * could write meanwhile (other screens, tab switches): a write queued behind
     * the import would land on the imported data, possibly on a different row
     * with the same id.
     */
    val isImporting: StateFlow<Boolean>

    /** The last successful export, `null` if there was none. */
    val lastExport: Flow<LastExport?>

    /**
     * The pre-import safety copies, newest first (the newest holds the data from
     * before the last successful import, the next one the data from before the
     * import before that).
     */
    val safetyCopies: Flow<List<SafetyCopy>>

    /**
     * Writes a backup of all data to [destinationUri] (a Storage Access Framework
     * document) and records it as [lastExport]. On failure the partly written
     * document is deleted where possible. [onProgress] gets values in 0..1.
     */
    suspend fun export(destinationUri: String, onProgress: (Float) -> Unit = {}): BackupCounts

    /**
     * Reads and validates the backup at [sourceUri] into temporary storage without
     * touching any data, and returns its preview. Replaces an earlier prepared import.
     */
    suspend fun prepareImport(sourceUri: String): ImportPreview

    /**
     * Like [prepareImport], for the safety copy with [copyId] ([SafetyCopy.id]),
     * or the newest one if `null`. Committing it saves a safety copy of the current
     * data first, like any import.
     */
    suspend fun prepareSafetyCopyRestore(copyId: Long? = null): ImportPreview

    /**
     * Replaces all data with the prepared backup: first saves a safety copy of the
     * current data, then replaces everything in one transaction. Returns the
     * imported counts. Runs to completion even if the caller is cancelled.
     */
    suspend fun commitImport(preview: ImportPreview, onProgress: (Float) -> Unit = {}): BackupCounts

    /** Drops a prepared import (Cancel in the preview). */
    suspend fun discardImport(preview: ImportPreview)

    companion object {
        /** MIME type of backup files, for the Storage Access Framework pickers. */
        const val MIME_TYPE: String = "application/zip"

        /** Types accepted when picking a backup to import (providers label zips differently). */
        val IMPORT_MIME_TYPES: Array<String> =
            arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip", "application/octet-stream")

        /** Default export file name, e.g. `cinexperience-backup-2026-10-03.zip`. */
        fun suggestedFileName(date: LocalDate): String = "cinexperience-backup-$date.zip"
    }
}
