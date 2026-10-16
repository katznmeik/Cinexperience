package io.github.katznmeik.cinexperience.core.data.backup

import io.github.katznmeik.cinexperience.core.data.db.MetadataEntity
import io.github.katznmeik.cinexperience.core.data.db.SeedSnapshotEntity
import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_COLUMNS
import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_ROWS
import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_SEAT_ID
import io.github.katznmeik.cinexperience.core.model.TextLimits.MAX_LONG_TEXT_LENGTH
import io.github.katznmeik.cinexperience.core.model.TextLimits.MAX_NAME_LENGTH
import io.github.katznmeik.cinexperience.core.model.Visit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The validator's size limits (spec §8): a backup whose texts, layouts or seat ids
 * are beyond what this app can store and show is refused before anything is
 * touched, with a message that names the problem; data exactly at the limits
 * exports and imports.
 */
internal class BackupLimitsTest : BackupTestBase() {

    // Exports and imports are compared row by row: no seeding in between.
    override val seedAfterImport: Boolean = false

    /** U+1F3AC CLAPPER BOARD: one code point, two chars. */
    private val emoji = "\uD83C\uDFAC"

    private val smallLayout = LayoutJson.encode(SeatLayout.fromPatterns("SS|SS", firstSeatId = 1)) // ids 1..4

    private fun cinema() = BackupCinema(1, "Kino", "Street 1", "Notes", isPreloaded = false, seedKey = null, isFavourite = false)

    private fun hall() = BackupHall(
        id = 2, cinemaId = 1, name = "Saal 1", layoutJson = smallLayout, screenWidth = 4f, screenDistance = 1.5f,
        referenceImagePath = null, notes = "Notes", isPreloaded = false, seedKey = null, nextSeatId = 5, screenOffset = 0f,
    )

    private fun visit() = BackupVisit(
        id = 3, hallId = 2, date = "2026-10-01", filmTitle = "Film", seatId = 1, screeningFormat = null,
        hallRatings = BackupHallRatings(null, null, null, null, null, null), seatRating = null,
        cinemaRatings = BackupCinemaRatings(null, null, null, null), note = "Note", photoPath = null,
    )

    private fun data(
        cinema: BackupCinema = cinema(),
        hall: BackupHall = hall(),
        visit: BackupVisit = visit(),
        snapshot: BackupSeedSnapshot = BackupSeedSnapshot("hall", "kino/saal-1", "layout", smallLayout),
        metadata: BackupMetadata = BackupMetadata("seed_version", "5"),
    ) = BackupData(
        backupVersion = BackupFormat.BACKUP_VERSION,
        cinemas = listOf(cinema),
        halls = listOf(hall),
        visits = listOf(visit),
        seatOverrides = listOf(BackupSeatOverride(2, 1, "GREEN", isFavourite = true)),
        seedSnapshots = listOf(snapshot),
        metadata = listOf(metadata),
    )

    private fun problem(data: BackupData): String {
        try {
            BackupValidator.validate(data, emptySet())
        } catch (p: ValidationProblem) {
            return p.detail
        }
        fail("Expected the data to be refused")
        error("unreachable")
    }

    /** [json] padded with spaces (valid JSON whitespace) to exactly [length] chars. */
    private fun padded(json: String, length: Int): String = "{" + " ".repeat(length - json.length) + json.substring(1)

    /**
     * A [MAX_ROWS] × [MAX_COLUMNS] layout with the highest seat ids, its JSON padded
     * as far as the validator accepts (twice its own encoding plus 1,024 chars, see
     * `LayoutJson.isPadded`; `BackupLayoutAndScreenLimitsTest` checks the padding).
     */
    private val largestLayout = SeatLayout.create(MAX_ROWS, MAX_COLUMNS, firstSeatId = MAX_SEAT_ID - MAX_ROWS * MAX_COLUMNS)
    private val largestLayoutJson = LayoutJson.encode(largestLayout).let { padded(it, 2 * it.length + 1024) }

    private val name = "N".repeat(MAX_NAME_LENGTH - 10) + emoji.repeat(10) // 1,000 code points, 1,010 chars
    private val longText = "L".repeat(MAX_LONG_TEXT_LENGTH)
    private fun tooLong(text: String) = text + "x"

    private fun atTheLimits() = data(
        cinema = BackupCinema(1, name, name, longText, isPreloaded = true, seedKey = "c" + name.drop(1), isFavourite = true),
        hall = hall().copy(
            name = name, layoutJson = largestLayoutJson, notes = longText, isPreloaded = true, seedKey = "h" + name.drop(1),
            nextSeatId = MAX_SEAT_ID, screenWidth = 0.5f, screenDistance = 0f,
        ),
        visit = visit().copy(filmTitle = longText, note = longText, seatId = MAX_SEAT_ID - 1),
        snapshot = BackupSeedSnapshot(name, name, name, "v".repeat(LayoutJson.MAX_JSON_LENGTH)),
        metadata = BackupMetadata(name, "v".repeat(LayoutJson.MAX_JSON_LENGTH)),
    ).copy(seatOverrides = listOf(BackupSeatOverride(2, MAX_SEAT_ID - 1, "GREEN", isFavourite = true)))

    @Test
    fun dataAtTheLimitsIsAccepted() {
        assertTrue(largestLayoutJson.length < LayoutJson.MAX_JSON_LENGTH)
        BackupValidator.validate(data(), emptySet())
        BackupValidator.validate(atTheLimits(), emptySet())
    }

    @Test
    fun everyTextOverItsLimitIsRefused() {
        val cases: List<Pair<BackupData, String>> = listOf(
            data(cinema = cinema().copy(name = tooLong(name))) to "the name of cinema 1 is too long.",
            data(cinema = cinema().copy(address = tooLong(name))) to "the address of cinema “Kino” is too long.",
            data(cinema = cinema().copy(notes = tooLong(longText))) to "the notes of cinema “Kino” are too long.",
            data(cinema = cinema().copy(seedKey = tooLong(name))) to "a cinema key is too long.",
            data(hall = hall().copy(name = tooLong(name))) to "the name of hall 2 is too long.",
            data(hall = hall().copy(notes = tooLong(longText))) to "the notes of hall “Saal 1” are too long.",
            data(hall = hall().copy(seedKey = tooLong(name))) to "a hall key is too long.",
            data(hall = hall().copy(referenceImagePath = "reference/" + "a".repeat(MAX_NAME_LENGTH))) to
                "the reference image of hall “Saal 1” has an unsafe file name.",
            data(visit = visit().copy(filmTitle = tooLong(longText))) to "the film title of visit 3 is too long.",
            data(visit = visit().copy(note = tooLong(longText))) to "the note of visit 3 is too long.",
            data(visit = visit().copy(photoPath = "photos/" + "a".repeat(MAX_NAME_LENGTH))) to
                "the photo of visit 3 has an unsafe file name.",
            data(snapshot = BackupSeedSnapshot(tooLong(name), "k", "f", null)) to "its seed records are invalid.",
            data(snapshot = BackupSeedSnapshot("hall", tooLong(name), "f", null)) to "its seed records are invalid.",
            data(snapshot = BackupSeedSnapshot("hall", "k", tooLong(name), null)) to "its seed records are invalid.",
            data(snapshot = BackupSeedSnapshot("hall", "k", "f", "v".repeat(LayoutJson.MAX_JSON_LENGTH + 1))) to
                "its seed records are invalid.",
            data(metadata = BackupMetadata(tooLong(name), "1")) to "its metadata is invalid.",
            data(metadata = BackupMetadata("other", "v".repeat(LayoutJson.MAX_JSON_LENGTH + 1))) to "its metadata is invalid.",
        )
        for ((data, expected) in cases) assertEquals(expected, problem(data))
    }

    @Test
    fun textLimitsCountCodePoints() {
        val emojiName = emoji.repeat(MAX_NAME_LENGTH) // 2,000 chars
        BackupValidator.validate(data(cinema = cinema().copy(name = emojiName)), emptySet())
        assertEquals("the name of cinema 1 is too long.", problem(data(cinema = cinema().copy(name = emojiName + emoji))))
    }

    @Test
    fun aLayoutBeyondTheLimitsIsTooLarge() {
        val expected = "the seat layout of hall “Saal 1” is too large."
        val tallRows = List(MAX_ROWS + 1) { "{\"cells\":\"S\"}" }.joinToString(",")
        val tall = "{\"formatVersion\":1,\"rows\":[$tallRows]}"
        val wide = "{\"formatVersion\":1,\"rows\":[{\"cells\":\"${"S".repeat(MAX_COLUMNS + 1)}\"}]}"
        val highIds = "{\"formatVersion\":1,\"nextSeatId\":${MAX_SEAT_ID + 1},\"rows\":[{\"cells\":\"S\",\"seatIds\":[$MAX_SEAT_ID]}]}"
        for (json in listOf(tall, wide, highIds)) {
            assertEquals(json, expected, problem(data(hall = hall().copy(layoutJson = json, nextSeatId = 1))))
        }
        // Over the JSON limit, even if the layout itself is small.
        val long = padded(smallLayout, LayoutJson.MAX_JSON_LENGTH + 1)
        assertEquals(expected, problem(data(hall = hall().copy(layoutJson = long))))
        // A layout that is merely unreadable keeps its message.
        assertEquals(
            "the seat layout of hall “Saal 1” can't be read by this app version.",
            problem(data(hall = hall().copy(layoutJson = "{\"formatVersion\":99,\"rows\":[]}"))),
        )
    }

    @Test
    fun seatIdsAboveTheLimitAreRefused() {
        val mark = "hall “Saal 1” has an invalid seat id mark."
        assertEquals(mark, problem(data(hall = hall().copy(nextSeatId = MAX_SEAT_ID + 1))))
        assertEquals(mark, problem(data(hall = hall().copy(nextSeatId = Int.MAX_VALUE))))
        assertEquals(mark, problem(data(hall = hall().copy(nextSeatId = 0))))
        // A seat id is always below the mark, so at most MAX_SEAT_ID - 1.
        val seat = "visit 3 has an invalid seat."
        assertEquals(seat, problem(data(visit = visit().copy(seatId = MAX_SEAT_ID))))
        assertEquals(seat, problem(data(visit = visit().copy(seatId = Int.MAX_VALUE))))
        assertEquals(seat, problem(data(visit = visit().copy(seatId = 0))))
        BackupValidator.validate(data(visit = visit().copy(seatId = MAX_SEAT_ID - 1)), emptySet())
    }

    @Test
    fun aScreenMustHaveAWidthAndCanNotBeBehindTheFirstRow() {
        val expected = "hall “Saal 1” has an invalid screen size."
        for ((width, distance) in listOf(0f to 1f, -0f to 1f, -1f to 1f, 4f to -0.5f, Float.NaN to 1f, 4f to Float.POSITIVE_INFINITY)) {
            assertEquals("$width / $distance", expected, problem(data(hall = hall().copy(screenWidth = width, screenDistance = distance))))
        }
        BackupValidator.validate(data(hall = hall().copy(screenWidth = 0.5f, screenDistance = 0f)), emptySet())
        BackupValidator.validate(data(hall = hall().copy(screenWidth = null, screenDistance = null)), emptySet())
    }

    // ---- Through the whole import ----

    private fun write(name: String, bytes: ByteArray): String = File(deviceA.root, name).apply { writeBytes(bytes) }.path

    /** The [valid] backup with its data changed by [transform]. */
    private fun modified(valid: File, name: String, transform: (BackupData) -> BackupData): String {
        val entries = readZip(valid)
        val i = entries.indexOfFirst { it.first == BackupFormat.DATA }
        val data = BackupFormat.strictJson.decodeFromString(BackupData.serializer(), entries[i].second.decodeToString())
        entries[i] = BackupFormat.DATA to BackupFormat.writeJson.encodeToString(BackupData.serializer(), transform(data)).toByteArray()
        return write(name, zipBytes(entries))
    }

    @Test
    fun aCraftedBackupIsRefusedBeforeAnythingIsTouched() = runTest {
        buildRealisticData()
        val valid = File(deviceA.document("valid.zip"))
        deviceA.repository.export(valid.path)
        val before = deviceA.snapshot()
        val copiesBefore = deviceA.safetyCopyFiles()
        val firstCinema = before.cinemas.first().id
        val bigRows = List(1000) { "{\"cells\":\"${"S".repeat(MAX_COLUMNS)}\"}" }.joinToString(",")
        val crafted = listOf(
            // A 3 MB name would make its row unreadable for Android (2 MB per row).
            modified(valid, "name.zip") { d ->
                d.copy(cinemas = d.cinemas.map { if (it.id == firstCinema) it.copy(name = "N".repeat(3_000_000)) else it })
            } to "The backup is damaged: the name of cinema $firstCinema is too long.",
            modified(valid, "layout.zip") { d ->
                d.copy(halls = d.halls.mapIndexed { i, h -> if (i == 0) h.copy(layoutJson = "{\"formatVersion\":1,\"rows\":[$bigRows]}") else h })
            } to "is too large.",
            modified(valid, "mark.zip") { d -> d.copy(halls = d.halls.mapIndexed { i, h -> if (i == 0) h.copy(nextSeatId = Int.MAX_VALUE) else h }) } to
                "has an invalid seat id mark.",
        )
        for ((path, message) in crafted) {
            val e = try {
                deviceA.repository.prepareImport(path)
                fail("Expected $path to be refused")
                error("unreachable")
            } catch (e: BackupException) {
                e
            }
            assertEquals(e.message, BackupError.INVALID_DATA, e.error)
            assertTrue(e.message, e.message!!.endsWith(message))
            assertEquals(before, deviceA.snapshot())
            assertEquals(copiesBefore, deviceA.safetyCopyFiles())
            assertTrue(deviceA.stagingIsEmpty())
        }
    }

    @Test
    fun dataAtTheLimitsExportsAndImports() = runTest {
        // Saved through the repositories, like the app does.
        val cinemaId = cinemas.createCinema(name, name, longText)
        val hallId = halls.createHall(cinemaId, name)
        val empty = halls.getHall(hallId)!!
        halls.updateHall(empty.copy(layout = largestLayout, notes = longText, screenWidth = 0.5f, screenDistance = 0f))
        visits.saveVisit(
            Visit(hallId = hallId, date = LocalDate.of(2026, 10, 1), filmTitle = longText, note = longText, seatId = SeatId(MAX_SEAT_ID - 1)),
        )
        overrides.setFavouriteSeat(hallId, SeatId(MAX_SEAT_ID - 1))
        // What only an import or the seeder writes: a layout JSON, seed records and metadata at their limits.
        val second = halls.createHall(cinemaId, "Saal 2")
        db.hallDao().update(db.hallDao().get(second)!!.copy(layoutJson = largestLayoutJson, nextSeatId = MAX_SEAT_ID))
        db.seedSnapshotDao().putAll(listOf(SeedSnapshotEntity(name, name, name, "v".repeat(LayoutJson.MAX_JSON_LENGTH))))
        db.metadataDao().put(MetadataEntity(name, "v".repeat(LayoutJson.MAX_JSON_LENGTH)))
        val expected = deviceA.snapshot()
        assertEquals(MAX_SEAT_ID, expected.halls.single { it.id == hallId }.nextSeatId)

        val doc = deviceA.document("limits.zip")
        deviceA.repository.export(doc)
        val deviceB = newDevice("b")
        File(doc).copyTo(File(deviceB.document("limits.zip")))
        val preview = deviceB.repository.prepareImport(deviceB.document("limits.zip"))
        deviceB.repository.commitImport(preview)
        assertEquals(expected, deviceB.snapshot())
    }
}
