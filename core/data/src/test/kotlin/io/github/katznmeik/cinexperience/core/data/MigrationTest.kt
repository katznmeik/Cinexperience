package io.github.katznmeik.cinexperience.core.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun exportedVersion1SchemaMatchesTheEntities() = runTest {
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

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
