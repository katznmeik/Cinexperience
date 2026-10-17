package io.github.katznmeik.cinexperience.core.data.backup

import io.github.katznmeik.cinexperience.core.data.repository.RoomCinemaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Backups and safety copies written by v1.0 (backup version 1, before favourite
 * cinemas and screen offsets) still import, through [BackupMigrations]. The
 * fixture in `src/test/resources/backup/v1` is hand-written in the version 1
 * shape and must never be changed.
 */
internal class BackupVersion1Test : BackupTestBase() {

    // The fixture is compared row by row, so the bundled seed must not add to it after an
    // import (it would as soon as seedVersion moves past the fixture's version 2).
    override val seedAfterImport: Boolean = false

    private val fixture = File("src/test/resources/backup/v1")
    private val manifest = File(fixture, "manifest.json").readText()
    private val data = File(fixture, "data.json").readText()
    private val photo = ByteArray(2000) { it.toByte() }

    private fun version1Zip(dataJson: String = data): ByteArray = zipBytes(
        listOf(
            BackupFormat.MANIFEST to manifest.toByteArray(),
            BackupFormat.DATA to dataJson.toByteArray(),
            "photos/visit-1.jpg" to photo,
        ),
    )

    private fun write(name: String, bytes: ByteArray): String = File(deviceA.root, name).apply { writeBytes(bytes) }.path

    /** Every row of the fixture as it must look after the import: unchanged, plus the version 2 defaults. */
    private fun assertFixtureImported(data: BackupData) {
        assertEquals(BackupFormat.BACKUP_VERSION, data.backupVersion)
        assertEquals(
            listOf(
                BackupCinema(5, "delphi LUX", "Kantstraße 10", null, true, "delphi-lux", isFavourite = false),
                BackupCinema(14, "Kiezkino", null, "Small and cosy", false, null, isFavourite = false),
            ),
            data.cinemas,
        )
        assertEquals(
            listOf(
                BackupHall(
                    id = 11, cinemaId = 5, name = "Saal Eins",
                    layoutJson = "{\"formatVersion\":1,\"nextSeatId\":5,\"rows\":[{\"cells\":\"SS|SS\",\"seatIds\":[1,2,3,4]}]}",
                    screenWidth = 3.5f, screenDistance = 2f, referenceImagePath = null, notes = "Row 1 is close",
                    isPreloaded = true, seedKey = "delphi-lux/saal-1", nextSeatId = 5, screenOffset = 0f,
                ),
                BackupHall(
                    id = 37, cinemaId = 14, name = "Saal A", layoutJson = null, screenWidth = null, screenDistance = null,
                    referenceImagePath = null, notes = null, isPreloaded = false, seedKey = null, nextSeatId = 1, screenOffset = 0f,
                ),
            ),
            data.halls,
        )
        val visit = data.visits.single { it.id == 1L }
        assertEquals(
            listOf<Any?>("Perfect Days", 3, "OMU", 4, "Great sound", "photos/visit-1.jpg", "2026-09-12"),
            listOf(visit.filmTitle, visit.seatId, visit.screeningFormat, visit.seatRating, visit.note, visit.photoPath, visit.date),
        )
        assertEquals(BackupHallRatings(5, 4, null, 3, null, 2), visit.hallRatings)
        assertEquals(BackupCinemaRatings(5, null, 3, null), visit.cinemaRatings)
        assertEquals(2, data.visits.size)
        assertEquals(listOf(BackupSeatOverride(11, 3, "GREEN", true)), data.seatOverrides)
        assertEquals(listOf(BackupSeedSnapshot("hall", "delphi-lux/saal-1", "screenWidth", "3.5")), data.seedSnapshots)
        assertEquals(listOf(BackupMetadata("seed_version", "2")), data.metadata)
    }

    @Test
    fun aVersion1BackupImportsWithTheDefaults() = runTest {
        deviceA.seed()
        val preview = deviceA.repository.prepareImport(write("v1.zip", version1Zip()))
        assertEquals(1, preview.backupVersion)
        assertEquals(BackupCounts(2, 2, 2, 1, 1, 0), preview.counts)
        deviceA.repository.commitImport(preview)

        assertFixtureImported(deviceA.snapshot())
        assertTrue(File(deviceA.filesDir, "photos/visit-1.jpg").readBytes().contentEquals(photo))
        // The repositories read the defaults: no favourite cinema, screens centred.
        val details = RoomCinemaRepository(db, Dispatchers.Default).observeCinemaDetails().first()
        assertTrue(details.none { it.cinema.isFavourite })
        assertTrue(details.flatMap { it.halls }.all { it.hall.screenOffset == 0f })

        // Exporting it again writes the current version, which imports to the same data.
        val again = deviceA.document("again.zip")
        deviceA.repository.export(again)
        val deviceB = newDevice("b")
        val previewB = deviceB.repository.prepareImport(again)
        assertEquals(BackupFormat.BACKUP_VERSION, previewB.backupVersion)
        deviceB.repository.commitImport(previewB)
        assertFixtureImported(deviceB.snapshot())
    }

    @Test
    fun aVersion1SafetyCopyCanStillBeRestored() = runTest {
        deviceA.seed()
        // A safety copy that v1.0 left behind.
        val folder = File(deviceA.filesDir, SafetyCopies.FOLDER).apply { mkdirs() }
        SafetyCopies(folder).newFile(Instant.parse("2026-10-01T18:30:00Z")).writeBytes(version1Zip())
        assertFixtureImported(deviceA.latestCopyData()!!)

        val preview = deviceA.repository.prepareSafetyCopyRestore()
        assertEquals(ImportSource.SAFETY_COPY, preview.source)
        deviceA.repository.commitImport(preview)
        assertFixtureImported(deviceA.snapshot())
    }

    @Test
    fun version1DataIsStillParsedStrictly() = runTest {
        deviceA.seed()
        val cases = mapOf(
            // Fields that version 1 didn't have are not merged, they are an error.
            "v1-with-favourite.zip" to data.replace("\"seedKey\": \"delphi-lux\"", "\"seedKey\": \"delphi-lux\", \"isFavourite\": true"),
            "v1-with-offset.zip" to data.replace("\"nextSeatId\": 5", "\"nextSeatId\": 5, \"screenOffset\": 2.0"),
            // Missing and unknown fields are rejected as before.
            "v1-missing.zip" to data.replace("\"notes\": \"Small and cosy\",", ""),
            "v1-unknown.zip" to data.replace("\"nextSeatId\": 1", "\"nextSeatId\": 1, \"colour\": \"RED\""),
        )
        for ((name, json) in cases) {
            assertTrue(name, json != data)
            val before = deviceA.snapshot()
            try {
                deviceA.repository.prepareImport(write(name, version1Zip(json)))
                fail("$name was accepted")
            } catch (e: BackupException) {
                assertEquals(name, BackupError.INVALID_DATA, e.error)
            }
            assertEquals(before, deviceA.snapshot())
        }
    }
}
