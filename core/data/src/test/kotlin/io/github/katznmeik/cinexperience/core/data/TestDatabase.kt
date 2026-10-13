package io.github.katznmeik.cinexperience.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import io.github.katznmeik.cinexperience.core.data.repository.RoomCinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.RoomHallRepository
import io.github.katznmeik.cinexperience.core.data.repository.RoomSeatOverrideRepository
import io.github.katznmeik.cinexperience.core.data.repository.RoomVisitRepository
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Base class for tests against a fresh in-memory database on Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
internal abstract class DatabaseTest {
    protected lateinit var db: CinexDatabase
    protected lateinit var cinemas: RoomCinemaRepository
    protected lateinit var halls: RoomHallRepository
    protected lateinit var visits: RoomVisitRepository
    protected lateinit var overrides: RoomSeatOverrideRepository

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CinexDatabase::class.java)
            .build()
        cinemas = RoomCinemaRepository(db, Dispatchers.Default)
        halls = RoomHallRepository(db, Dispatchers.Default)
        visits = RoomVisitRepository(db, Dispatchers.Default)
        overrides = RoomSeatOverrideRepository(db)
    }

    @After
    fun closeDatabase() {
        db.close()
    }
}
