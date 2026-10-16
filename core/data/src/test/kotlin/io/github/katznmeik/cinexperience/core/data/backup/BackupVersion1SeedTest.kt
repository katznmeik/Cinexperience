package io.github.katznmeik.cinexperience.core.data.backup

import io.github.katznmeik.cinexperience.core.data.seed.SeedParser
import io.github.katznmeik.cinexperience.core.data.seed.Seeder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A v1.0 backup (backup version 1, seed version 2) imported as the app does it:
 * with seeding afterwards, which brings the backup's seed state up to the shipped
 * seed. [BackupVersion1Test] compares the rows with seeding off; this checks that
 * the seeder only adds what is missing and leaves the backup's rows alone, above
 * all "Saal Eins", a renamed seed hall with a layout of its own, a visit on a
 * seat and a favourite seat. Reads the fixture in `src/test/resources/backup/v1`,
 * which is never changed.
 */
internal class BackupVersion1SeedTest : BackupTestBase() {

    private val fixture = File("src/test/resources/backup/v1")
    private val photo = ByteArray(2000) { it.toByte() }

    private fun version1Backup(): String {
        val zip = zipBytes(
            listOf(
                BackupFormat.MANIFEST to File(fixture, "manifest.json").readBytes(),
                BackupFormat.DATA to File(fixture, "data.json").readBytes(),
                "photos/visit-1.jpg" to photo,
            ),
        )
        return File(deviceA.root, "v1.zip").apply { writeBytes(zip) }.path
    }

    @Test
    fun theSeederOnlyAddsWhatTheVersion1BackupLacks() = runTest {
        assertTrue(seedAfterImport)
        deviceA.seed()
        deviceA.repository.commitImport(deviceA.repository.prepareImport(version1Backup()))
        val data = deviceA.snapshot()

        // The backup's rows, unchanged (plus the version 2 defaults).
        assertEquals(
            listOf(
                BackupCinema(5, "delphi LUX", "Kantstraße 10", null, true, "delphi-lux", isFavourite = false),
                BackupCinema(14, "Kiezkino", null, "Small and cosy", false, null, isFavourite = false),
            ),
            data.cinemas.filter { it.id == 5L || it.id == 14L },
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
            data.halls.filter { it.id == 11L || it.id == 37L },
        )
        assertEquals(listOf(1L, 2L), data.visits.map { it.id })
        val visit = data.visits.first()
        assertEquals(listOf<Any?>(11L, 3, "photos/visit-1.jpg"), listOf(visit.hallId, visit.seatId, visit.photoPath))
        assertEquals(listOf(BackupSeatOverride(11, 3, "GREEN", true)), data.seatOverrides)
        assertTrue(File(deviceA.filesDir, "photos/visit-1.jpg").readBytes().contentEquals(photo))

        // Everything else of the shipped seed was added: the other cinemas, and delphi LUX's
        // other halls inside the imported cinema, preloaded like it.
        val seed = SeedParser.parse(seedAsset)
        assertEquals(seed.seedVersion.toString(), data.metadata.single { it.key == Seeder.KEY_SEED_VERSION }.value)
        assertEquals(seed.cinemas.size + 1, data.cinemas.size)
        assertEquals(seed.cinemas.map { it.seedKey }.toSet(), data.cinemas.mapNotNull { it.seedKey }.toSet())
        assertEquals(seed.cinemas.sumOf { it.halls.size } + 1, data.halls.size)
        assertEquals(seed.cinemas.flatMap { it.halls }.map { it.seedKey }.toSet(), data.halls.mapNotNull { it.seedKey }.toSet())
        val luxHalls = data.halls.filter { it.cinemaId == 5L }
        assertEquals(seed.cinemas.single { it.seedKey == "delphi-lux" }.halls.size, luxHalls.size)
        assertTrue(luxHalls.all { it.isPreloaded && it.layoutJson != null })

        // Up to date now: seeding again (as at the next app start) changes nothing.
        assertFalse(deviceA.seed())
        assertEquals(data, deviceA.snapshot())
    }
}
