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
 * - **Export** reads every row in one transaction, then streams the zip to the
 *   document. Only after the stream was closed without error is the export
 *   recorded as [lastExport]; on any failure the document is deleted (best effort)
 *   and an error is reported. Process death mid-export can leave an incomplete
 *   file at the destination: it lacks the zip's directory or entries, so an import
 *   rejects it (missing manifest, data or files).
 * - **Import** is two-step: [prepareImport] unpacks into `cacheDir/backup-import/`
 *   and validates everything ([BackupReader]) without touching the data;
 *   [commitImport] writes a safety copy of the current data ([SafetyCopies]) and
 *   then replaces everything atomically ([BackupRestorer]). Afterwards seeding runs
 *   with the backup's seed version and snapshot, so it adds nothing that the
 *   backup's own seed state doesn't call for.
 *
 * Operations are serialised by a mutex. Every failure becomes a [BackupException].
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
    private val safetyCopies = SafetyCopies(File(filesDir, SafetyCopies.FOLDER))
    private val stagingRoot = File(cacheDir, STAGING_FOLDER)

    /** The prepared import; guarded by [mutex]. */
    private var staged: StagedBackup? = null

    /** Bumped whenever the last export or the safety copies change. */
    private val changes = MutableStateFlow(0)

    override val lastExport: Flow<LastExport?> =
        changes.map { lastExportStore.read() }.distinctUntilChanged().flowOn(ioDispatcher)

    override val latestSafetyCopy: Flow<SafetyCopy?> =
        changes.map { safetyCopies.latest()?.let { SafetyCopy(it.createdAt) } }.distinctUntilChanged().flowOn(ioDispatcher)

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
            val exportedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
            val counts = try {
                documents.openOutput(destinationUri).use { out ->
                    writer.write(data, appVersion(), exportedAt, out) { progress ->
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

    override suspend fun prepareSafetyCopyRestore(): ImportPreview = prepare(ImportSource.SAFETY_COPY) {
        val copy = safetyCopies.latest()
            ?: throw BackupException(BackupError.NO_SAFETY_COPY, "There is no safety copy to restore.")
        copy.file.inputStream()
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
                    try {
                        if (!current.filesIntact()) throw expired()
                        awaitReady()
                        val before = db.readBackupData()
                        writeSafetyCopy(before) { onProgress(it * SAFETY_COPY_SHARE) }
                        val restorer = BackupRestorer(db, filesDir, clock::millis, failpoint)
                        val imported = restorer.replace(current, before) {
                            onProgress(SAFETY_COPY_SHARE + it * (1 - SAFETY_COPY_SHARE))
                        }
                        seed()
                        imported.counts()
                    } catch (e: Throwable) {
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

    /** Writes [data] to a new safety copy (via a temporary file) and prunes old copies. */
    private fun writeSafetyCopy(data: BackupData, onProgress: (Float) -> Unit) {
        val createdAt: Instant = clock.instant()
        val target = safetyCopies.fileFor(createdAt)
        val temp = File(target.parentFile, target.name + SafetyCopies.TEMP_SUFFIX)
        try {
            target.parentFile?.mkdirs()
            FileOutputStream(temp).use { out ->
                writer.write(data, appVersion(), createdAt.truncatedTo(ChronoUnit.SECONDS), out, onProgress)
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
        safetyCopies.prune()
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
