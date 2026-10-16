package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.seed.SeedParser
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.TextLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shipped seed stays within the limits that the repositories and the backup
 * validator enforce, so seeded data always exports (and imports again).
 */
class SeedLimitsTest {

    private val seed = SeedParser.parse(File("src/main/assets/${DatabaseConfig.SEED_ASSET_PATH}").readText())
    private val halls = seed.cinemas.flatMap { it.halls }

    @Test
    fun everySeedLayoutIsWithinTheLimits() {
        assertEquals(38, halls.size)
        for (hall in halls) {
            val layout = LayoutJson.fromJson(checkNotNull(hall.layout) { hall.seedKey })
            assertTrue(hall.seedKey, layout.rowCount <= SeatLayout.MAX_ROWS)
            assertTrue(hall.seedKey, layout.columnCount <= SeatLayout.MAX_COLUMNS)
            assertTrue(hall.seedKey, layout.nextSeatId <= SeatLayout.MAX_SEAT_ID)
            // As stored: with its ids, and shifted above a hall mark far beyond any real one.
            assertTrue(hall.seedKey, LayoutJson.encode(layout).length <= LayoutJson.MAX_JSON_LENGTH / 10)
            val shifted = layout.withIdsShiftedBy(SeatLayout.MAX_SEAT_ID / 2)
            assertTrue(hall.seedKey, LayoutJson.encode(shifted).length <= LayoutJson.MAX_JSON_LENGTH / 10)
        }
        // The largest seed halls, far below the limits.
        assertEquals(40, halls.maxOf { LayoutJson.fromJson(it.layout!!).rowCount })
        assertEquals(34, halls.maxOf { LayoutJson.fromJson(it.layout!!).columnCount })
    }

    @Test
    fun everySeedTextIsWithinTheLimits() {
        val texts = seed.cinemas.flatMap { listOf(it.seedKey, it.name, it.address) } + halls.flatMap { listOf(it.seedKey, it.name) }
        for (text in texts) assertTrue("$text", TextLimits.fits(text, TextLimits.MAX_NAME_LENGTH))
        assertTrue(texts.filterNotNull().maxOf { it.length } < 100)
    }

    @Test
    fun everySeedScreenHasAWidthAndIsInFrontOfTheFirstRow() {
        for (hall in halls) {
            hall.screenWidth?.let { assertTrue(hall.seedKey, it.isFinite() && it > 0f) }
            hall.screenDistance?.let { assertTrue(hall.seedKey, it.isFinite() && it >= 0f) }
        }
    }
}
