package io.github.katznmeik.cinexperience.core.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.katznmeik.cinexperience.core.data.db.CinemaEntity
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import io.github.katznmeik.cinexperience.core.data.db.HallEntity
import io.github.katznmeik.cinexperience.core.data.repository.RoomCinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.RoomTicketRepository
import io.github.katznmeik.cinexperience.core.data.repository.RoomVisitRepository
import io.github.katznmeik.cinexperience.core.data.repository.SaveTicketResult
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

/**
 * Schema and migration tests against the exported schemas in `core/data/schemas`
 * (wired into the unit test assets by the Room convention plugin).
 *
 * For every new schema version N, add a test that creates version N-1 with
 * [helper], inserts representative rows with raw SQL, runs
 * `helper.runMigrationsAndValidate(DB, N, true, *CinexDatabase.ALL_MIGRATIONS)`
 * and checks the migrated data through the DAOs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
internal class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CinexDatabase::class.java)

    @Test
    fun version1DatabaseOpensWithTheCurrentEntities() = runTest {
        // Create the database purely from the committed 1.json and fill it with raw SQL.
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO cinema (id, name, address, notes, is_preloaded, seed_key) " +
                    "VALUES (1, 'Kino', NULL, NULL, 1, 'kino')",
            )
            db.execSQL(
                "INSERT INTO hall (id, cinema_id, name, layout_json, screen_width, screen_distance, " +
                    "reference_image_path, notes, is_preloaded, seed_key) " +
                    "VALUES (1, 1, 'Saal 1', '{\"formatVersion\":1,\"rows\":[{\"cells\":\"SS\"}]}', 6.0, 2.0, " +
                    "NULL, NULL, 1, 'kino/saal-1')",
            )
            db.execSQL(
                "INSERT INTO visit (id, hall_id, date, seat_id, seat_rating, rating_sound) " +
                    "VALUES (1, 1, '2026-10-13', 2, 4, 5)",
            )
        }

        // Opening it with Room validates the schema against the compiled entities
        // (identity hash and table info) and runs all migrations.
        val room = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CinexDatabase::class.java,
            TEST_DB,
        ).addMigrations(*CinexDatabase.ALL_MIGRATIONS).build()
        try {
            val visit = room.visitDao().get(1)!!
            assertEquals(2, visit.seatId)
            assertEquals(5, visit.ratingSound)
            val list = room.visitDao().observeAllWithContext().first()
            assertEquals("Kino", list.single().cinemaName)
        } finally {
            room.close()
        }
    }

    @Test
    fun migration1To2KeepsAllDataAndAddsTheDefaults() = runTest {
        val layoutJson = "{\"formatVersion\":1,\"nextSeatId\":5,\"rows\":[{\"cells\":\"SS|SS\",\"seatIds\":[1,2,3,4]}]}"
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO cinema (id, name, address, notes, is_preloaded, seed_key) VALUES " +
                    "(1, 'delphi LUX', 'Kantstraße 10', 'Seed', 1, 'delphi-lux'), " +
                    "(2, 'Kiezkino', NULL, 'Mine', 0, NULL)",
            )
            db.execSQL(
                "INSERT INTO hall (id, cinema_id, name, layout_json, screen_width, screen_distance, " +
                    "reference_image_path, notes, is_preloaded, seed_key, next_seat_id) VALUES " +
                    "(1, 1, 'Saal 1', '$layoutJson', 12.5, 2.0, 'reference/hall-1.jpg', 'Front', 1, 'delphi-lux/saal-1', 9), " +
                    "(2, 2, 'Saal A', NULL, NULL, NULL, NULL, NULL, 0, NULL, 1)",
            )
            db.execSQL(
                "INSERT INTO visit (id, hall_id, date, film_title, seat_id, seat_rating, rating_sound, photo_path) " +
                    "VALUES (1, 1, '2026-10-13', 'Aftersun', 3, 4, 5, 'photos/visit-1.jpg')",
            )
            db.execSQL("INSERT INTO seat_override (hall_id, seat_id, colour, is_favourite) VALUES (1, 3, 'GREEN', 1)")
            db.execSQL("INSERT INTO seed_snapshot (kind, seed_key, field, value) VALUES ('hall', 'delphi-lux/saal-1', 'screenWidth', '12.5')")
            db.execSQL("INSERT INTO app_metadata (`key`, value) VALUES ('seed_version', '2')")
        }

        // Runs MIGRATION_1_2 and checks the result against the exported 2.json.
        helper.runMigrationsAndValidate(TEST_DB, 2, true, *CinexDatabase.ALL_MIGRATIONS).close()

        val room = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CinexDatabase::class.java,
            TEST_DB,
        ).addMigrations(*CinexDatabase.ALL_MIGRATIONS).build()
        try {
            assertEquals(
                listOf(
                    CinemaEntity(1, "delphi LUX", "Kantstraße 10", "Seed", true, "delphi-lux", isFavourite = false),
                    CinemaEntity(2, "Kiezkino", null, "Mine", false, null, isFavourite = false),
                ),
                room.backupDao().cinemas(),
            )
            assertEquals(
                listOf(
                    HallEntity(
                        1, 1, "Saal 1", layoutJson, 12.5f, 2f, "reference/hall-1.jpg", "Front", true, "delphi-lux/saal-1",
                        nextSeatId = 9, screenOffset = 0f,
                    ),
                    HallEntity(2, 2, "Saal A", null, null, null, null, null, false, null, nextSeatId = 1, screenOffset = 0f),
                ),
                room.backupDao().halls(),
            )
            val visit = room.visitDao().get(1)!!
            assertEquals(listOf(3, 4, 5), listOf(visit.seatId, visit.seatRating, visit.ratingSound))
            assertEquals("photos/visit-1.jpg", visit.photoPath)
            assertEquals(1, room.backupDao().seatOverrides().count { it.isFavourite && it.colour == "GREEN" })
            assertEquals(1, room.backupDao().seedSnapshots().size)
            assertEquals("2", room.metadataDao().get("seed_version"))

            // The models read the defaults: not a favourite, screen centred.
            val cinemas = RoomCinemaRepository(room, Dispatchers.Default)
            val detail = cinemas.observeCinemaDetail(1).first()!!
            assertFalse(detail.cinema.isFavourite)
            assertEquals(0f, detail.halls.single().hall.screenOffset)
            assertEquals(12.5f, detail.halls.single().hall.screenWidth)
        } finally {
            room.close()
        }
    }

    @Test
    fun migration2To3KeepsAllDataAddsNoStartTimesAndNoTickets() = runTest {
        val layoutJson = "{\"formatVersion\":1,\"nextSeatId\":3,\"rows\":[{\"cells\":\"SS\",\"seatIds\":[1,2]}]}"
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO cinema (id, name, address, notes, is_preloaded, seed_key, is_favourite) " +
                    "VALUES (1, 'Kiezkino', NULL, NULL, 0, NULL, 1)",
            )
            db.execSQL(
                "INSERT INTO hall (id, cinema_id, name, layout_json, screen_width, screen_distance, " +
                    "reference_image_path, notes, is_preloaded, seed_key, next_seat_id, screen_offset) " +
                    "VALUES (1, 1, 'Saal A', '$layoutJson', 4.0, 1.5, NULL, NULL, 0, NULL, 3, -0.5)",
            )
            db.execSQL(
                "INSERT INTO visit (id, hall_id, date, film_title, seat_id, screening_format, seat_rating, " +
                    "rating_sound, cinema_rating_popcorn, note, photo_path) VALUES " +
                    "(7, 1, '2026-10-13', 'The Quiet Projectionist', 2, 'OMU', 4, 5, 3, 'Good', 'photos/visit-7.jpg'), " +
                    "(8, 1, '2026-10-24', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
            )
            db.execSQL("INSERT INTO seat_override (hall_id, seat_id, colour, is_favourite) VALUES (1, 2, 'RED', 0)")
            db.execSQL("INSERT INTO app_metadata (`key`, value) VALUES ('seed_version', '6')")
        }

        // Runs MIGRATION_2_3 and checks the result against the exported 3.json.
        helper.runMigrationsAndValidate(TEST_DB, 3, true, *CinexDatabase.ALL_MIGRATIONS).close()

        val room = openRoom()
        try {
            val before = listOf(room.visitDao().get(7)!!, room.visitDao().get(8)!!)
            assertEquals(listOf("The Quiet Projectionist", null), before.map { it.filmTitle })
            assertEquals(listOf(2, 4, 5, 3), with(before[0]) { listOf(seatId, seatRating, ratingSound, cinemaRatingPopcorn) })
            assertEquals(listOf("OMU", "Good", "photos/visit-7.jpg"), with(before[0]) { listOf(screeningFormat, note, photoPath) })
            assertTrue(before.all { it.startTime == null })
            assertTrue(room.backupDao().tickets().isEmpty())
            assertEquals(1, room.backupDao().seatOverrides().size)
            assertEquals(-0.5f, room.hallDao().get(1)!!.screenOffset)
            assertTrue(room.cinemaDao().get(1)!!.isFavourite)
            assertEquals("6", room.metadataDao().get("seed_version"))

            // The models: no start time, no ticket; the list says so.
            val visits = RoomVisitRepository(room, Dispatchers.Default)
            assertNull(visits.getVisit(7)!!.startTime)
            assertFalse(visits.observeAllVisits().first().any { it.hasTicket })
            assertNull(RoomTicketRepository(room, Dispatchers.Default).getTicket(7))

            // The new table works: a ticket for visit 8, deleted with it.
            val tickets = RoomTicketRepository(room, Dispatchers.Default)
            val visit = visits.getVisit(8)!!.copy(startTime = LocalTime.of(20, 15))
            val ticket = Ticket(8, BookingNumber("TEST123"), listOf(TicketSeat("1", "2")))
            assertEquals(SaveTicketResult.Saved(8, seatCleared = false), tickets.saveVisitWithTicket(visit, ticket))
            assertEquals(LocalTime.of(20, 15), visits.getVisit(8)!!.startTime)
            assertTrue(visits.deleteVisit(8))
            assertNull(tickets.findVisitId(BookingNumber("TEST123")))
        } finally {
            room.close()
        }
    }

    @Test
    fun migration1To3RunsBothSteps() = runTest {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO cinema (id, name, address, notes, is_preloaded, seed_key) VALUES (1, 'Kino', NULL, NULL, 1, 'kino')",
            )
            db.execSQL(
                "INSERT INTO hall (id, cinema_id, name, layout_json, screen_width, screen_distance, " +
                    "reference_image_path, notes, is_preloaded, seed_key) " +
                    "VALUES (1, 1, 'Saal 1', NULL, NULL, NULL, NULL, NULL, 1, 'kino/saal-1')",
            )
            db.execSQL("INSERT INTO visit (id, hall_id, date, seat_rating) VALUES (1, 1, '2026-09-12', 3)")
        }

        helper.runMigrationsAndValidate(TEST_DB, 3, true, *CinexDatabase.ALL_MIGRATIONS).close()

        val room = openRoom()
        try {
            val visit = room.visitDao().get(1)!!
            assertEquals(3, visit.seatRating)
            assertNull(visit.startTime)
            assertFalse(room.cinemaDao().get(1)!!.isFavourite)
            assertEquals(0f, room.hallDao().get(1)!!.screenOffset)
            assertTrue(room.backupDao().tickets().isEmpty())
        } finally {
            room.close()
        }
    }

    private fun openRoom(): CinexDatabase =
        Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CinexDatabase::class.java, TEST_DB)
            .addMigrations(*CinexDatabase.ALL_MIGRATIONS)
            .build()

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
