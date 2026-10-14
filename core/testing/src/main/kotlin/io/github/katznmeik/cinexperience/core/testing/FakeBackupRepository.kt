package io.github.katznmeik.cinexperience.core.testing

import io.github.katznmeik.cinexperience.core.data.backup.BackupCounts
import io.github.katznmeik.cinexperience.core.data.backup.BackupRepository
import io.github.katznmeik.cinexperience.core.data.backup.ImportPreview
import io.github.katznmeik.cinexperience.core.data.backup.ImportSource
import io.github.katznmeik.cinexperience.core.data.backup.LastExport
import io.github.katznmeik.cinexperience.core.data.backup.SafetyCopy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/**
 * In-memory [BackupRepository] for ViewModel tests. Every call is recorded; set
 * the `…Failure` properties to make calls throw, and [gate] to hold an export or
 * import mid-way (after reporting [progress]) until it is completed.
 */
class FakeBackupRepository(
    lastExport: LastExport? = null,
    safetyCopy: SafetyCopy? = null,
) : BackupRepository {

    override val lastExport = MutableStateFlow(lastExport)
    override val latestSafetyCopy = MutableStateFlow(safetyCopy)

    /** True while [commitImport] runs (e.g. held by [gate]); tests may also set it directly. */
    override val isImporting = MutableStateFlow(false)

    /** What an export writes and what a prepared import contains. */
    var counts = BackupCounts(cinemas = 13, halls = 36, visits = 27, overrides = 4, photos = 9, referenceImages = 2)

    /** The time of the next export, and of the backup a prepared import comes from. */
    var now: Instant = Instant.parse("2026-10-03T12:05:00Z")

    var exportFailure: Exception? = null
    var prepareFailure: Exception? = null
    var commitFailure: Exception? = null

    /** Progress reported before waiting for [gate]. */
    var progress: Float = 0.5f
    var gate: CompletableDeferred<Unit>? = null

    val exportedTo = mutableListOf<String>()
    val preparedFrom = mutableListOf<String>()
    val committed = mutableListOf<ImportPreview>()
    val discarded = mutableListOf<ImportPreview>()

    private var nextPreview = 0

    override suspend fun export(destinationUri: String, onProgress: (Float) -> Unit): BackupCounts {
        exportedTo += destinationUri
        onProgress(progress)
        gate?.await()
        exportFailure?.let { throw it }
        lastExport.value = LastExport(now, counts)
        return counts
    }

    override suspend fun prepareImport(sourceUri: String): ImportPreview {
        preparedFrom += sourceUri
        return preview(ImportSource.FILE)
    }

    override suspend fun prepareSafetyCopyRestore(): ImportPreview {
        preparedFrom += SAFETY_COPY
        return preview(ImportSource.SAFETY_COPY)
    }

    private suspend fun preview(source: ImportSource): ImportPreview {
        gate?.await()
        prepareFailure?.let { throw it }
        return ImportPreview("preview-${++nextPreview}", source, now, "0.1.0", 1, counts)
    }

    override suspend fun commitImport(preview: ImportPreview, onProgress: (Float) -> Unit): BackupCounts {
        committed += preview
        isImporting.value = true
        try {
            onProgress(progress)
            gate?.await()
            commitFailure?.let { throw it }
            latestSafetyCopy.value = SafetyCopy(now)
            return preview.counts
        } finally {
            isImporting.value = false
        }
    }

    override suspend fun discardImport(preview: ImportPreview) {
        discarded += preview
    }

    companion object {
        /** Recorded in [preparedFrom] for [prepareSafetyCopyRestore]. */
        const val SAFETY_COPY: String = "safety-copy"
    }
}
