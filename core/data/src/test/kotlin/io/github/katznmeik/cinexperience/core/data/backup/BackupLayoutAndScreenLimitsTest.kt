package io.github.katznmeik.cinexperience.core.data.backup

import io.github.katznmeik.cinexperience.core.data.DatabaseConfig
import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.seed.SeedParser
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_COLUMNS
import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_ROWS
import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_SEAT_ID
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The validator's limits on a layout's row labels, its JSON's padding and the
 * screen's size: a crafted backup can't bring in a label or screen that makes the
 * map scale to nothing, or a layout JSON padded far beyond what the app writes,
 * while everything the app itself writes passes.
 */
internal class BackupLayoutAndScreenLimitsTest {

    /** U+1F3AC CLAPPER BOARD: one code point, two chars. */
    private val emoji = "🎬"

    private fun hall(layout: SeatLayout = SeatLayout.fromPatterns("SS|SS", firstSeatId = 1)) = BackupHall(
        id = 2, cinemaId = 1, name = "Saal 1", layoutJson = LayoutJson.encode(layout), screenWidth = 4f, screenDistance = 1.5f,
        referenceImagePath = null, notes = null, isPreloaded = false, seedKey = null, nextSeatId = layout.nextSeatId,
        screenOffset = 0f,
    )

    private fun data(hall: BackupHall = hall()) = BackupData(
        backupVersion = BackupFormat.BACKUP_VERSION,
        cinemas = listOf(BackupCinema(1, "Kino", null, null, isPreloaded = false, seedKey = null, isFavourite = false)),
        halls = listOf(hall),
        visits = emptyList(),
        tickets = emptyList(),
        seatOverrides = emptyList(),
        seedSnapshots = emptyList(),
        metadata = emptyList(),
    )

    private fun accepted(data: BackupData) = BackupValidator.validate(data, emptySet())

    private fun problem(data: BackupData): String {
        try {
            BackupValidator.validate(data, emptySet())
        } catch (p: ValidationProblem) {
            return p.detail
        }
        fail("Expected the data to be refused")
        error("unreachable")
    }

    private fun withRowLabel(label: String) =
        hall(SeatLayout.fromPatterns("SS", "SS", firstSeatId = 1).updateRow(1) { it.copy(label = label) })

    // ---- Row labels ----

    @Test
    fun aRowLabelOfMoreThanSixteenCodePointsIsRefused() {
        val expected = "a row label in the seat layout of hall “Saal 1” is too long."
        assertEquals(expected, problem(data(withRowLabel("R".repeat(17)))))
        assertEquals(expected, problem(data(withRowLabel(emoji.repeat(17)))))
        assertEquals(expected, problem(data(withRowLabel("R".repeat(250_000)))))
    }

    @Test
    fun rowLabelsUpToSixteenCodePointsAreAccepted() {
        accepted(data(withRowLabel("R".repeat(16))))
        accepted(data(withRowLabel(emoji.repeat(16)))) // 32 chars
        accepted(data(withRowLabel("-"))) // a row without a name
        accepted(data(withRowLabel("R1")))
    }

    // ---- Screen size ----

    @Test
    fun aScreenWiderThanTwiceTheWidestGridIsRefused() {
        val expected = "hall “Saal 1” has an invalid screen size."
        for (width in listOf(120.5f, 121f, 3e38f, Float.MAX_VALUE)) {
            assertEquals("$width", expected, problem(data(hall().copy(screenWidth = width))))
        }
        accepted(data(hall().copy(screenWidth = 120f)))
    }

    @Test
    fun aScreenFartherThanTheEditorAllowsIsRefused() {
        val expected = "hall “Saal 1” has an invalid screen size."
        for (distance in listOf(15.5f, 16f, 3e38f, Float.MAX_VALUE)) {
            assertEquals("$distance", expected, problem(data(hall().copy(screenDistance = distance))))
        }
        accepted(data(hall().copy(screenDistance = 15f)))
    }

    // ---- Padding ----

    /** [json] padded with spaces (valid JSON whitespace) to exactly [length] chars. */
    private fun padded(json: String, length: Int): String = "{" + " ".repeat(length - json.length) + json.substring(1)

    @Test
    fun aLayoutJsonMuchLongerThanItsOwnEncodingIsRefused() {
        val canonical = hall().layoutJson!!
        val limit = 2 * canonical.length + 1024
        val expected = "the seat layout of hall “Saal 1” is too large."
        assertEquals(expected, problem(data(hall().copy(layoutJson = padded(canonical, limit + 1)))))
        assertEquals(expected, problem(data(hall().copy(layoutJson = padded(canonical, LayoutJson.MAX_JSON_LENGTH)))))
        // Some slack for JSON written differently (spacing, field order).
        accepted(data(hall().copy(layoutJson = padded(canonical, limit))))
    }

    @Test
    fun theAppsOwnEncodingOfEverySeedLayoutPasses() {
        val seed = SeedParser.parse(File("src/main/assets/${DatabaseConfig.SEED_ASSET_PATH}").readText())
        val layouts = seed.cinemas.flatMap { it.halls }.map { LayoutJson.fromJson(it.layout!!) }
        assertEquals(38, layouts.size)
        for (layout in layouts) {
            // As the seeder stores it: ids from 1, or shifted far above a hall mark.
            for (stored in listOf(layout, layout.withIdsShiftedBy(MAX_SEAT_ID / 2))) {
                val json = LayoutJson.encode(stored)
                assertEquals(json, LayoutJson.encode(LayoutJson.decode(json)))
                accepted(data(hall(stored)))
            }
        }
    }

    @Test
    fun theAppsOwnEncodingOfTheLargestPossibleLayoutPasses() {
        // 50 × 60 seats with nine-digit ids, a fixed label on every seat, the longest
        // row labels and every row setting: the longest JSON the app can write.
        var layout = SeatLayout.create(MAX_ROWS, MAX_COLUMNS, firstSeatId = MAX_SEAT_ID - MAX_ROWS * MAX_COLUMNS)
        for (r in 0 until MAX_ROWS) {
            layout = layout.updateRow(r) {
                it.copy(
                    label = emoji.repeat(16),
                    numbering = SeatNumbering.RESTART_PER_BLOCK,
                    numberingDirection = NumberingDirection.RIGHT_TO_LEFT,
                    blockStartNumbers = listOf(9999),
                    halfSeatOffset = true,
                )
            }
            for (c in 0 until MAX_COLUMNS) layout = layout.withFixedLabel(r, c, emoji.repeat(2))
        }
        val json = LayoutJson.encode(layout)
        assertTrue(json.length < LayoutJson.MAX_JSON_LENGTH / 2)
        assertEquals(json, LayoutJson.encode(LayoutJson.decode(json)))
        accepted(data(hall(layout).copy(screenWidth = 2f * MAX_COLUMNS, screenDistance = 15f)))
    }
}
