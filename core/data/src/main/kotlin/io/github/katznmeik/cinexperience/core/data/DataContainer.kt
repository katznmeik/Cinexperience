package io.github.katznmeik.cinexperience.core.data

import android.content.Context
import android.util.Log
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
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

    private val seeding: Deferred<Unit> by lazy {
        scope.async(ioDispatcher) {
            try {
                Seeder(database) {
                    appContext.assets.open(DatabaseConfig.SEED_ASSET_PATH).bufferedReader().use { it.readText() }
                }.seedIfNeeded()
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

    private companion object {
        const val TAG = "CinexData"
    }
}
