package io.github.katznmeik.cinexperience.core.data.backup

import io.github.katznmeik.cinexperience.core.data.repository.RoomTicketRepository
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Backups written by v1.1–v1.3 (backup version 2, before start times and
 * tickets) still import, through the [BackupMigrations] step 2 → 3, and so do
 * those of v1.0 (1 → 2 → 3). The fixture in `src/test/resources/backup/v2` is
 * hand-written in the version 2 shape and must never be changed.
 */
internal class BackupVersion2Test : BackupTestBase() {

    // The fixture is compared row by row: nothing may be seeded on top.
    override val seedAfterImport: Boolean = false

    private val fixture = File("src/test/resources/backup/v2")
    private val manifest = File(fixture, "manifest.json").readText()
    private val data = File(fixture, "data.json").readText()

    private fun version2Zip(dataJson: String = data): ByteArray =
        zipBytes(listOf(BackupFormat.MANIFEST to manifest.toByteArray(), BackupFormat.DATA to dataJson.toByteArray()))

    private fun write(name: String, bytes: ByteArray): String = File(deviceA.root, name).apply { writeBytes(bytes) }.path

    /** The fixture as it must look after the import: unchanged, no start times, no tickets. */
    private fun assertFixtureImported(data: BackupData) {
        assertEquals(BackupFormat.BACKUP_VERSION, data.backupVersion)
        assertEquals(listOf(BackupCinema(3, "Kiezkino", "Weserstraße 1", null, false, null, isFavourite = true)), data.cinemas)
        assertEquals(-1.5f, data.halls.single().screenOffset)
        assertEquals(listOf(21L, 22L), data.visits.map { it.id })
        assertTrue(data.visits.all { it.startTime == null })
        val visit = data.visits.first()
        assertEquals(
            listOf<Any?>("2026-10-04", "The Quiet Projectionist", 5, "OV", 5, "Row 1 is fine"),
            listOf(visit.date, visit.filmTitle, visit.seatId, visit.screeningFormat, visit.seatRating, visit.note),
        )
        assertEquals(BackupHallRatings(4, 5, null, 3, null, null), visit.hallRatings)
        assertEquals(listOf(BackupSeatOverride(8, 5, null, isFavourite = true)), data.seatOverrides)
        assertEquals(listOf(BackupMetadata("seed_version", "6")), data.metadata)
        assertTrue(data.tickets.isEmpty())
    }

    @Test
    fun theStepAddsNoStartTimesAndNoTickets() {
        val v2 = Json.parseToJsonElement(data).jsonObject
        val v3 = BackupMigrations.migrate(v2, 2)
        assertEquals(JsonPrimitive(3), v3["backupVersion"])
        assertEquals(listOf(JsonNull, JsonNull), v3.getValue("visits").jsonArray.map { it.jsonObject["startTime"] })
        assertEquals(JsonArray(emptyList()), v3["tickets"])
        // Everything else is left as it was.
        for (key in listOf("cinemas", "halls", "seatOverrides", "seedSnapshots", "metadata")) assertEquals(key, v2[key], v3[key])
        // A version 2 file with a version 3 field is invalid.
        assertThrows(IllegalArgumentException::class.java) {
            BackupMigrations.migrate(Json.parseToJsonElement("""{"backupVersion": 2, "tickets": []}""").jsonObject, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupMigrations.migrate(Json.parseToJsonElement("""{"backupVersion": 2, "visits": [{"startTime": "20:15"}]}""").jsonObject, 2)
        }
    }

    @Test
    fun aVersion2BackupImportsAndExportsAsVersion3() = runTest {
        val preview = deviceA.repository.prepareImport(write("v2.zip", version2Zip()))
        assertEquals(2, preview.backupVersion)
        assertEquals(BackupCounts(1, 1, 2, 1, 0, 0), preview.counts)
        deviceA.repository.commitImport(preview)
        assertFixtureImported(deviceA.snapshot())
        assertNull(visits.getVisit(21)!!.startTime)
        assertNull(RoomTicketRepository(db, Dispatchers.Default).getTicket(21))

        val again = deviceA.document("again.zip")
        deviceA.repository.export(again)
        val deviceB = newDevice("b")
        val previewB = deviceB.repository.prepareImport(again)
        assertEquals(3, previewB.backupVersion)
        deviceB.repository.commitImport(previewB)
        assertFixtureImported(deviceB.snapshot())
    }

    @Test
    fun aVersion1BackupGoesThroughBothSteps() = runTest {
        val v1 = File("src/test/resources/backup/v1")
        val zip = zipBytes(
            listOf(
                BackupFormat.MANIFEST to File(v1, "manifest.json").readBytes(),
                BackupFormat.DATA to File(v1, "data.json").readBytes(),
                "photos/visit-1.jpg" to ByteArray(2000) { it.toByte() },
            ),
        )
        val preview = deviceA.repository.prepareImport(write("v1.zip", zip))
        assertEquals(1, preview.backupVersion)
        deviceA.repository.commitImport(preview)
        val imported = deviceA.snapshot()
        assertEquals(listOf(1L, 2L), imported.visits.map { it.id })
        assertTrue(imported.visits.all { it.startTime == null })
        assertTrue(imported.tickets.isEmpty())
        assertNull(RoomTicketRepository(db, Dispatchers.Default).findVisitId(BookingNumber("TEST123")))
    }

    @Test
    fun version2DataIsStillParsedStrictly() = runTest {
        val cases = mapOf(
            // Fields that version 2 didn't have are not merged, they are an error.
            "v2-with-start-time.zip" to data.replace("\"date\": \"2026-10-04\",", "\"date\": \"2026-10-04\", \"startTime\": \"20:15\","),
            "v2-with-tickets.zip" to data.replace("\"seatOverrides\": [", "\"tickets\": [], \"seatOverrides\": ["),
            // Missing and unknown fields are rejected as before.
            "v2-missing.zip" to data.replace("\"address\": \"Weserstraße 1\",", ""),
            "v2-unknown.zip" to data.replace("\"seatRating\": 5,", "\"seatRating\": 5, \"bookingNumber\": \"TEST123\","),
        )
        for ((name, json) in cases) {
            assertTrue(name, json != data)
            val before = deviceA.snapshot()
            try {
                deviceA.repository.prepareImport(write(name, version2Zip(json)))
                fail("$name was accepted")
            } catch (e: BackupException) {
                assertEquals(name, BackupError.INVALID_DATA, e.error)
            }
            assertEquals(before, deviceA.snapshot())
        }
    }
}
