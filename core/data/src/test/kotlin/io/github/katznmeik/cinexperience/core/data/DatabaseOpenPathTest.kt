package io.github.katznmeik.cinexperience.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.katznmeik.cinexperience.core.data.db.CinemaEntity
import io.github.katznmeik.cinexperience.core.data.db.CinexDatabase
import io.github.katznmeik.cinexperience.core.data.db.HallEntity
import io.github.katznmeik.cinexperience.core.data.db.SeatOverrideEntity
import io.github.katznmeik.cinexperience.core.data.db.VisitEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The app's own open path: a version 1 database file under the app's database
 * name, opened through the production [CinexDatabase.build] (not a test builder,
 * unlike [MigrationTest]). A destructive fallback there would wipe the rows, and
 * missing migrations would make the open fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
internal class DatabaseOpenPathTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CinexDatabase::class.java)

    @Test
    fun buildOpensAVersion1DatabaseAndKeepsItsRows() = runTest {
        val layoutJson = "{\"formatVersion\":1,\"nextSeatId\":7,\"rows\":[{\"cells\":\"SS|SS\",\"seatIds\":[3,4,5,6]}]}"
        helper.createDatabase(DatabaseConfig.DATABASE_NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO cinema (id, name, address, notes, is_preloaded, seed_key) " +
                    "VALUES (5, 'Kiezkino', 'Weserstraße 1', 'Mine', 0, NULL)",
            )
            db.execSQL(
                "INSERT INTO hall (id, cinema_id, name, layout_json, screen_width, screen_distance, " +
                    "reference_image_path, notes, is_preloaded, seed_key, next_seat_id) " +
                    "VALUES (8, 5, 'Saal A', '$layoutJson', 6.0, 1.5, NULL, 'Row 2 is best', 0, NULL, 11)",
            )
            db.execSQL(
                "INSERT INTO visit (id, hall_id, date, film_title, seat_id, seat_rating, rating_sound) " +
                    "VALUES (13, 8, '2026-10-13', 'Aftersun', 4, 4, 5)",
            )
            db.execSQL("INSERT INTO seat_override (hall_id, seat_id, colour, is_favourite) VALUES (8, 5, 'GREEN', 1)")
        }

        val room = CinexDatabase.build(ApplicationProvider.getApplicationContext())
        try {
            assertEquals(3, room.openHelper.readableDatabase.version)
            // Rows and ids kept; the version 2 columns get their defaults (not a favourite, screen centred).
            assertEquals(
                listOf(CinemaEntity(5, "Kiezkino", "Weserstraße 1", "Mine", false, null, isFavourite = false)),
                room.backupDao().cinemas(),
            )
            assertEquals(
                listOf(
                    HallEntity(
                        8, 5, "Saal A", layoutJson, 6f, 1.5f, null, "Row 2 is best", false, null,
                        nextSeatId = 11, screenOffset = 0f,
                    ),
                ),
                room.backupDao().halls(),
            )
            assertEquals(
                listOf(
                    VisitEntity(
                        id = 13, hallId = 8, date = LocalDate.of(2026, 10, 13), filmTitle = "Aftersun", seatId = 4,
                        screeningFormat = null, ratingSound = 5, ratingPicture = null, ratingScreenSize = null,
                        ratingSeatComfort = null, ratingLegroom = null, ratingSteepness = null, seatRating = 4,
                        cinemaRatingAtmosphere = null, cinemaRatingBarSnacks = null, cinemaRatingPopcorn = null,
                        cinemaRatingSeatComfort = null, note = null, photoPath = null,
                    ),
                ),
                room.backupDao().visits(),
            )
            assertEquals(listOf(SeatOverrideEntity(8, 5, "GREEN", true)), room.backupDao().seatOverrides())
            // The raw v2 columns, not only the entity defaults.
            room.openHelper.readableDatabase.query("SELECT is_favourite FROM cinema WHERE id = 5").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
            room.openHelper.readableDatabase.query("SELECT screen_offset, next_seat_id FROM hall WHERE id = 8").use {
                it.moveToFirst()
                assertEquals(0.0, it.getDouble(0), 0.0)
                assertEquals(11, it.getInt(1))
            }
        } finally {
            room.close()
        }
    }
}
