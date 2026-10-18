package io.github.katznmeik.cinexperience.core.data.contract

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import io.github.katznmeik.cinexperience.core.data.db.toEntity
import io.github.katznmeik.cinexperience.core.data.repository.RoomCinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.RoomHallRepository
import io.github.katznmeik.cinexperience.core.data.repository.RoomSeatOverrideRepository
import io.github.katznmeik.cinexperience.core.data.repository.RoomTicketRepository
import io.github.katznmeik.cinexperience.core.data.repository.RoomVisitRepository
import io.github.katznmeik.cinexperience.core.model.SeatOverride
import io.github.katznmeik.cinexperience.core.model.Visit
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [RepositoryContract] on the Room repositories, with a fresh in-memory database per test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
internal class RoomRepositoryContractTest : RepositoryContract() {

    private lateinit var db: CinexDatabase

    override fun createRepositories(): Repositories {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CinexDatabase::class.java).build()
        return Repositories(
            cinemas = RoomCinemaRepository(db, Dispatchers.Default),
            halls = RoomHallRepository(db, Dispatchers.Default),
            visits = RoomVisitRepository(db, Dispatchers.Default),
            overrides = RoomSeatOverrideRepository(db),
            tickets = RoomTicketRepository(db, Dispatchers.Default),
        )
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    override suspend fun insertUnchecked(visit: Visit) {
        db.visitDao().insert(visit.toEntity())
    }

    override suspend fun insertUnchecked(override: SeatOverride) {
        db.seatOverrideDao().insertAll(listOf(override.toEntity()))
    }
}
