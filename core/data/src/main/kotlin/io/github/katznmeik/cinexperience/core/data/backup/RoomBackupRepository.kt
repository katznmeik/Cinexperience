package io.github.katznmeik.cinexperience.core.data.backup

import android.util.Log
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import io.github.katznmeik.cinexperience.core.data.repository.AwaitReady
import io.github.katznmeik.cinexperience.core.data.seed.Seeder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * [BackupRepository] on the Room database and the app-private files.
 *
 * - **Export** reads every row in one transaction and checks it with the
 *   import's validation (a backup this app couldn't import again is never
 *   written), then streams the zip to the document. Only after the stream was
 *   closed without error is the export recorded as [lastExport]; on any failure
 *   the document is deleted (best effort) and an error is reported. Process death
 *   mid-export can leave an incomplete file at the destination: it lacks the
 *   zip's directory or entries, so an import rejects it.
 * - **Import** is two-step: [prepareImport] unpacks into `cacheDir/backup-import/`
 *   and validates everything ([BackupReader]) without touching the data;
 *   [commitImport] makes sure a safety copy of the current data exists
 *   ([SafetyCopies]) and then replaces everything atomically ([BackupRestorer]).
 *   Afterwards seeding runs with the backup's seed version and snapshot, so it
 *   adds nothing that the backup's own seed state doesn't call for.
 * - **Safety copies:** if the newest copy already holds exactly the current data
 *   (e.g. after a failed attempt or process death) it is reused; otherwise a new
 *   one is written. A failed import deletes the copy it wrote; old copies are
 *   pruned only after a successful import. So the newest copy always holds the
 *   data from before the last successful import, and failed attempts never push
 *   an older copy out.
 *
 * Operations are serialised by a mutex. Failures become [BackupException]s,
 * including stack overflow and out-of-memory while reading a backup; only
 * cancellation and other fatal VM errors are passed on.
 */
internal class RoomBackupRepository(
    private val db: CinexDatabase,
    private val filesDir: File,
    cacheDir: File,
    private val documents: DocumentAccess,
    private val lastExportStore: LastExportStore,
    private val appVersion: () -> String,
    /** The seed asset, to bring an imported older seed state up to date; `null` = don't seed. */
    private val readSeed: (() -> String)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
    private val awaitReady: AwaitReady = {},
    limits: BackupLimits = BackupLimits(),
    private val failpoint: (ImportStep) -> Unit = {},
) : BackupRepository {

    private val mutex = Mutex()
    private val writer = BackupWriter(filesDir)
    private val reader = BackupReader(limits)
    private val copyStore = SafetyCopies(File(filesDir, SafetyCopies.FOLDER))
    private val stagingRoot = File(cacheDir, STAGING_FOLDER)

    /** The prepared import; guarded by [mutex]. */
    private var staged: StagedBackup? = null

    /** Bumped whenever the last export or the safety copies change. */
    private val changes = MutableStateFlow(0)

    private val importing = MutableStateFlow(false)

    override val isImporting: StateFlow<Boolean> = importing.asStateFlow()

    override val lastExport: Flow<LastExport?> =
        changes.map { lastExportStore.read() }.distinctUntilChanged().flowOn(ioDispatcher)

    override val safetyCopies: Flow<List<SafetyCopy>> =
        changes.map {
            copyStore.all().map { copy ->
                val manifest = try {
                    copy.file.inputStream().use { reader.readManifestOrNull(it) }
                } catch (e: IOException) {
                    null
                }
                SafetyCopy(copy.sequence, copy.createdAt, manifest?.counts?.toModel())
            }
        }.distinctUntilChanged().flowOn(ioDispatcher)

    override suspend fun export(destinationUri: String, onProgress: (Float) -> Unit): BackupCounts = mutex.withLock {
        withContext(ioDispatcher) {
            awaitReady()
            val job = coroutineContext.job
            val data = try {
                db.readBackupData()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw BackupException(BackupError.WRITE_FAILED, "Couldn't read your data for the backup.", e)
            }
            // Checked before the document is opened: nothing is written for invalid data.
            val prepared = try {
                writer.prepare(data)
            } catch (p: ValidationProblem) {
                throw BackupException(BackupError.INVALID_DATA, "Your data can't be backed up: ${p.detail}", p)
            }
            val exportedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
            val counts = try {
                documents.openOutput(destinationUri).use { out ->
                    writer.write(prepared, appVersion(), exportedAt, out) { progress ->
                        job.ensureActive()
                        onProgress(progress)
                    }
                }
            } catch (e: Throwable) {
                // Never leave a partial file that looks like a backup.
                documents.delete(destinationUri)
                if (e is CancellationException || e is BackupException || e !is Exception) throw e
                throw BackupException(BackupError.WRITE_FAILED, "Couldn't write the backup file. Nothing was exported.", e)
            }
            if (!lastExportStore.write(LastExport(exportedAt, counts))) Log.w(TAG, "Couldn't record the last export")
            changes.update { it + 1 }
            counts
        }
    }

    override suspend fun prepareImport(sourceUri: String): ImportPreview =
        prepare(ImportSource.FILE) { documents.openInput(sourceUri) }

    override suspend fun prepareSafetyCopyRestore(copyId: Long?): ImportPreview = prepare(ImportSource.SAFETY_COPY) {
        val copy = if (copyId == null) copyStore.latest() else copyStore.all().firstOrNull { it.sequence == copyId }
        copy?.file?.inputStream() ?: throw BackupException(
            BackupError.NO_SAFETY_COPY,
            if (copyId == null) "There is no safety copy to restore." else "This safety copy no longer exists.",
        )
    }

    private suspend fun prepare(source: ImportSource, open: () -> InputStream): ImportPreview = mutex.withLock {
        withContext(ioDispatcher) {
            clearStaging()
            val dir = File(stagingRoot, UUID.randomUUID().toString())
            try {
                val input = try {
                    open()
                } catch (e: BackupException) {
                    throw e
                } catch (e: Exception) { // IOException, SecurityException: no access
                    throw BackupException(BackupError.READ_FAILED, "Couldn't open the backup file.", e)
                }
                val result = input.use { reader.read(it, dir) }
                coroutineContext.ensureActive()
                staged = result
                result.preview(source)
            } catch (e: Throwable) {
                dir.deleteRecursively()
                throw when (e) {
                    is BackupException, is CancellationException -> e
                    is IOException -> BackupException(BackupError.READ_FAILED, "Couldn't read the backup file.", e)
                    is Exception -> BackupException(BackupError.INVALID_DATA, "The backup is damaged and can't be read.", e)
                    is StackOverflowError -> BackupException(BackupError.INVALID_DATA, "The backup is damaged and can't be read.", e)
                    is OutOfMemoryError -> BackupException(BackupError.TOO_LARGE, "This backup is too large to import.", e)
                    else -> e
                }
            }
        }
    }

    override suspend fun commitImport(preview: ImportPreview, onProgress: (Float) -> Unit): BackupCounts =
        // Once started, an import runs to the end: cancelling between the database
        // commit and the file clean-up must never be mistaken for a failure.
        withContext(NonCancellable) {
            mutex.withLock {
                withContext(ioDispatcher) {
                    val current = staged?.takeIf { it.id == preview.id } ?: throw expired()
                    importing.value = true
                    var newCopy: File? = null
                    var committed = false
                    try {
                        if (!current.filesIntact()) throw expired()
                        awaitReady()
                        val before = db.readBackupData()
                        val prepared = try {
                            writer.prepare(before)
                        } catch (p: ValidationProblem) {
                            throw BackupException(
                                BackupError.INVALID_DATA,
                                "Current data can't be backed up: ${p.detail.removeSuffix(".")} — import cancelled.",
                                p,
                            )
                        }
                        newCopy = ensureSafetyCopy(prepared) { onProgress(it * SAFETY_COPY_SHARE) }
                        val restorer = BackupRestorer(db, filesDir, clock::millis, failpoint)
                        val imported = restorer.replace(current, before) {
                            onProgress(SAFETY_COPY_SHARE + it * (1 - SAFETY_COPY_SHARE))
                        }
                        committed = true
                        pruneSafetyCopies()
                        seed()
                        imported.counts()
                    } catch (e: Throwable) {
                        // The import didn't happen: the copy written for it would hide the
                        // copy of the last successful import, so it goes.
                        if (!committed) newCopy?.delete()
                        throw when (e) {
                            is BackupException -> e
                            is Exception -> BackupException(
                                BackupError.WRITE_FAILED,
                                "Couldn't import the backup. Your data was not changed.",
                                e,
                            )
                            else -> e
                        }
                    } finally {
                        clearStaging()
                        importing.value = false
                        changes.update { it + 1 }
                    }
                }
            }
        }

    override suspend fun discardImport(preview: ImportPreview) {
        mutex.withLock {
            withContext(ioDispatcher) {
                if (staged?.id == preview.id) clearStaging()
            }
        }
    }

    /**
     * Deletes leftover staging files of an earlier process (e.g. killed while the
     * preview was open). Called at app start; keeps the files of a prepared import.
     */
    suspend fun clearLeftoverStaging() {
        mutex.withLock {
            withContext(ioDispatcher) {
                if (staged == null) stagingRoot.deleteRecursively()
            }
        }
    }

    /**
     * Makes sure a safety copy of [prepared] exists. Returns the file it wrote, or
     * `null` if the newest copy already holds exactly this data (nothing written).
     */
    private fun ensureSafetyCopy(prepared: PreparedBackup, onProgress: (Float) -> Unit): File? {
        val newest = copyStore.latest()
        val newestData = newest?.let { copy ->
            try {
                copy.file.inputStream().use { reader.readDataOrNull(it) }
            } catch (e: IOException) {
                null
            }
        }
        if (newestData == prepared.data) {
            onProgress(1f)
            return null
        }
        val createdAt: Instant = clock.instant()
        val target = copyStore.newFile(createdAt)
        val temp = File(target.parentFile, target.name + SafetyCopies.TEMP_SUFFIX)
        try {
            target.parentFile?.mkdirs()
            FileOutputStream(temp).use { out ->
                writer.write(prepared, appVersion(), createdAt.truncatedTo(ChronoUnit.SECONDS), out, onProgress)
                out.fd.sync()
            }
            if (!temp.renameTo(target)) throw IOException("Couldn't rename $temp")
        } catch (e: Exception) {
            temp.delete()
            throw BackupException(
                BackupError.WRITE_FAILED,
                "Couldn't save a safety copy of your current data, so nothing was imported. Is there enough free storage?",
                e,
            )
        }
        return target
    }

    /** After a successful import; the newest copy (this import's) is always kept. */
    private fun pruneSafetyCopies() {
        try {
            copyStore.prune()
        } catch (e: Exception) {
            Log.w(TAG, "Pruning safety copies failed", e)
        }
    }

    /** Applies a newer shipped seed to imported data, like at app start; failures are only logged. */
    private suspend fun seed() {
        val read = readSeed ?: return
        try {
            Seeder(db, read).seedIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Seeding after the import failed", e)
        }
    }

    private fun clearStaging() {
        staged = null
        stagingRoot.deleteRecursively()
    }

    private fun expired() = BackupException(
        BackupError.IMPORT_EXPIRED,
        "This import is no longer available. Please choose the backup again.",
    )

    companion object {
        private const val TAG = "CinexBackup"

        /** Staging folder of prepared imports under the cache dir. */
        internal const val STAGING_FOLDER: String = "backup-import"

        /** Share of the import progress for writing the safety copy. */
        private const val SAFETY_COPY_SHARE = 0.5f
    }
}
