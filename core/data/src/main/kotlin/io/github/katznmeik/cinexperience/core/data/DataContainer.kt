package io.github.katznmeik.cinexperience.core.data

import android.content.Context
import android.util.Log
import io.github.katznmeik.cinexperience.core.data.backup.BackupRepository
import io.github.katznmeik.cinexperience.core.data.backup.ContentResolverDocuments
import io.github.katznmeik.cinexperience.core.data.backup.LastExportStore
import io.github.katznmeik.cinexperience.core.data.backup.RoomBackupRepository
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import io.github.katznmeik.cinexperience.core.data.files.FileReferenceImageStore
import io.github.katznmeik.cinexperience.core.data.files.FileVisitPhotoStore
import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import io.github.katznmeik.cinexperience.core.data.files.VisitPhotoStore
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.data.repository.RoomCinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.RoomHallRepository
import io.github.katznmeik.cinexperience.core.data.repository.RoomSeatOverrideRepository
import io.github.katznmeik.cinexperience.core.data.repository.RoomVisitRepository
import io.github.katznmeik.cinexperience.core.data.repository.SeatOverrideRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.data.seed.Seeder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * The data layer's entry point for the app's manual DI container.
 *
 * Everything is created lazily: the database is opened on first use of a
 * repository, and at that moment seeding (spec §9) starts in [scope]. Repository
 * flows wait for seeding before their first emission, so the cinema list never
 * flashes empty on the very first start. Seeding errors are logged, not thrown.
 */
class DataContainer(
    context: Context,
    private val scope: CoroutineScope,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext

    private val database: CinexDatabase by lazy { CinexDatabase.build(appContext) }

    private fun readSeedAsset(): String =
        appContext.assets.open(DatabaseConfig.SEED_ASSET_PATH).bufferedReader().use { it.readText() }

    private val seeding: Deferred<Unit> by lazy {
        scope.async(ioDispatcher) {
            try {
                Seeder(database, ::readSeedAsset).seedIfNeeded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Seeding the database failed", e)
            }
            Unit
        }
    }

    private val awaitReady: suspend () -> Unit = { seeding.await() }

    val cinemaRepository: CinemaRepository by lazy {
        RoomCinemaRepository(database, computeDispatcher, awaitReady).also { seeding.start() }
    }

    val hallRepository: HallRepository by lazy {
        RoomHallRepository(database, computeDispatcher, awaitReady).also { seeding.start() }
    }

    val visitRepository: VisitRepository by lazy {
        RoomVisitRepository(database, computeDispatcher, awaitReady).also { seeding.start() }
    }

    val seatOverrideRepository: SeatOverrideRepository by lazy {
        RoomSeatOverrideRepository(database).also { seeding.start() }
    }

    /** Reference screenshots of the layout editor (spec §6.5), in app-private storage. */
    val referenceImageStore: ReferenceImageStore by lazy { FileReferenceImageStore(appContext, ioDispatcher) }

    /** Visit photos (spec §4.4), in app-private storage. */
    val visitPhotoStore: VisitPhotoStore by lazy { FileVisitPhotoStore(appContext, ioDispatcher) }

    /** Backup export and import (spec §8). */
    val backupRepository: BackupRepository get() = roomBackupRepository

    private val roomBackupRepository: RoomBackupRepository by lazy {
        RoomBackupRepository(
            db = database,
            filesDir = appContext.filesDir,
            cacheDir = appContext.cacheDir,
            documents = ContentResolverDocuments(appContext.contentResolver),
            lastExportStore = LastExportStore(
                appContext.getSharedPreferences(LastExportStore.PREFERENCES_NAME, Context.MODE_PRIVATE),
            ),
            appVersion = {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
            },
            readSeed = ::readSeedAsset,
            ioDispatcher = ioDispatcher,
            awaitReady = awaitReady,
        ).also { seeding.start() }
    }

    /**
     * The startup sweep: [sweepOrphanReferenceImages], [sweepOrphanVisitPhotos] and
     * the leftover backup import staging in the cache. Returns the total number of
     * deleted photos and reference images. It only looks into `files/reference/`,
     * `files/photos/` and the cache; the downloaded program (`files/program/`, owned
     * by core:program) is never touched (ProgramStorageTest in app).
     */
    suspend fun sweepOrphanFiles(now: Long = System.currentTimeMillis()): Int {
        clearLeftoverImportStaging()
        return sweepOrphanReferenceImages(now) + sweepOrphanVisitPhotos(now)
    }

    /**
     * Deletes `cacheDir/backup-import/` left by an earlier process (e.g. killed while
     * an import preview was open); a prepared import of this process is kept.
     * Failures are logged, not thrown.
     */
    suspend fun clearLeftoverImportStaging() {
        try {
            roomBackupRepository.clearLeftoverStaging()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Clearing the import staging failed", e)
        }
    }

    /**
     * Deletes reference screenshots that no hall points to, e.g. left behind when
     * the app was closed while an editor had an unsaved screenshot. Run once at app
     * start; only files older than [ORPHAN_MIN_AGE_MS] are deleted, so an editor
     * that imports a screenshot meanwhile is safe. Failures are logged, not thrown.
     * Returns the number of deleted files.
     */
    suspend fun sweepOrphanReferenceImages(now: Long = System.currentTimeMillis()): Int =
        withContext(ioDispatcher) {
            try {
                val referenced = database.hallDao().referenceImagePaths().toSet()
                referenceImageStore.deleteOrphans(referenced, modifiedBefore = now - ORPHAN_MIN_AGE_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Sweeping reference images failed", e)
                0
            }
        }

    /**
     * Deletes visit photos that no visit points to (e.g. left by a visit form that
     * was closed by the system before saving, or by visits deleted together with
     * their hall or cinema) and leftover camera captures. Same age rule and error
     * handling as [sweepOrphanReferenceImages].
     */
    suspend fun sweepOrphanVisitPhotos(now: Long = System.currentTimeMillis()): Int =
        withContext(ioDispatcher) {
            try {
                val referenced = database.visitDao().photoPaths().toSet()
                visitPhotoStore.deleteOrphans(referenced, modifiedBefore = now - ORPHAN_MIN_AGE_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Sweeping visit photos failed", e)
                0
            }
        }

    companion object {
        private const val TAG = "CinexData"

        /** Minimum age of an unreferenced reference screenshot or visit photo before the sweep deletes it. */
        const val ORPHAN_MIN_AGE_MS: Long = 60 * 60 * 1000L
    }
}
