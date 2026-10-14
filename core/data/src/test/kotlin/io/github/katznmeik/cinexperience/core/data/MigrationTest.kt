package io.github.katznmeik.cinexperience.core.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.katznmeik.cinexperience.core.data.db.CinemaEntity
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import io.github.katznmeik.cinexperience.core.data.db.HallEntity
import io.github.katznmeik.cinexperience.core.data.repository.RoomCinemaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
