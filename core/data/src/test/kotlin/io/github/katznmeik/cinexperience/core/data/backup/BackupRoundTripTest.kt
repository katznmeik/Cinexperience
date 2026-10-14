package io.github.katznmeik.cinexperience.core.data.backup

import io.github.katznmeik.cinexperience.core.data.repository.RoomCinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.RoomHallRepository
import io.github.katznmeik.cinexperience.core.data.save
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.model.labels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

internal class BackupRoundTripTest : BackupTestBase() {

    @Test
    fun exportWipeImportRestoresEveryRowAndFile() = runTest {
        buildRealisticData()
        val expected = deviceA.snapshot()
        val expectedFiles = deviceA.files()
        val doc = deviceA.document("backup.zip")

        val counts = deviceA.repository.export(doc)
        assertEquals(BackupCounts(cinemas = 14, halls = 37, visits = 4, overrides = 3, photos = 2, referenceImages = 2), counts)

        // A fresh install: seeded, no user data.
        val deviceB = newDevice("b")
        deviceB.seed()
        File(doc).copyTo(File(deviceB.document("backup.zip")))
        val preview = deviceB.repository.prepareImport(deviceB.document("backup.zip"))
        assertEquals(counts, preview.counts)
        assertEquals(Instant.parse("2026-10-03T12:05:00Z"), preview.exportedAt)
        assertEquals("1.2.3", preview.appVersion)
        assertEquals(2, preview.backupVersion)
        assertEquals(ImportSource.FILE, preview.source)

        assertEquals(counts, deviceB.repository.commitImport(preview))

        // Row by row (ids, layouts as stored, seat id marks, seed snapshot, seed version) and byte by byte.
        assertEquals(expected, deviceB.snapshot())
        assertEquals(expectedFiles, deviceB.files())
        assertTrue(deviceB.stagingIsEmpty())

        // Seeding afterwards adds and changes nothing.
        assertFalse(deviceB.seed())
        assertEquals(expected, deviceB.snapshot())
    }

    @Test
    fun favouriteCinemasAndScreenOffsetsSurviveTheRoundTrip() = runTest {
        buildRealisticData()
        val doc = deviceA.document("backup.zip")
        deviceA.repository.export(doc)

        // data.json carries both fields.
        val data = BackupFormat.strictJson.decodeFromString(
            BackupData.serializer(),
            readZip(File(doc)).single { it.first == BackupFormat.DATA }.second.decodeToString(),
        )
        assertEquals(setOf("delphi LUX", "Kiezkino"), data.cinemas.filter { it.isFavourite }.map { it.name }.toSet())
        assertEquals(mapOf("Saal Eins" to -1.5f, "Saal A" to 2f), data.halls.filter { it.screenOffset != 0f }.associate { it.name to it.screenOffset })

        val deviceB = newDevice("b")
        deviceB.seed()
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))

        val cinemasB = RoomCinemaRepository(deviceB.db, Dispatchers.Default)
        val details = cinemasB.observeCinemaDetails().first()
        assertEquals(setOf("delphi LUX", "Kiezkino"), details.filter { it.cinema.isFavourite }.map { it.cinema.name }.toSet())
        assertEquals(
            mapOf("Saal Eins" to -1.5f, "Saal A" to 2f),
            details.flatMap { it.halls }.filter { it.hall.screenOffset != 0f }.associate { it.hall.name to it.hall.screenOffset },
        )
        // The safety copy taken before the import holds device B's data in the same format, and validates.
        val copy = deviceB.latestCopyData()!!
        BackupValidator.validate(copy, copy.filePaths)
        assertTrue(copy.cinemas.none { it.isFavourite })
        assertTrue(copy.halls.all { it.screenOffset == 0f })
    }

    /**
     * Layouts of format 3 (numbering direction, fixed seat labels) go into the backup
     * verbatim, validate, import and land in the safety copy without a new
     * backupVersion: the DTOs didn't change, only the layout JSON they carry.
     */
    @Test
    fun yorckNumberedLayoutsSurviveExportImportAndSafetyCopies() = runTest {
        buildRealisticData()
        val userHall = halls.getHall(db.hallDao().getAll().single { it.name == "Saal A" }.id)!!
        val storedJson = db.hallDao().get(userHall.id)!!.layoutJson!!
        assertTrue(storedJson, storedJson.contains("\"formatVersion\":3"))
        assertEquals(NumberingDirection.RIGHT_TO_LEFT, userHall.layout!!.rows[2].numberingDirection)
        val labels = userHall.layout!!.labels()
        assertEquals(listOf("R", "5", "4", "3", "2", "1"), userHall.layout!!.rows[2].seatIds.map { labels.labelOf(it)!!.seat })

        val doc = deviceA.document("backup.zip")
        deviceA.repository.export(doc)
        val data = BackupFormat.strictJson.decodeFromString(
            BackupData.serializer(),
            readZip(File(doc)).single { it.first == BackupFormat.DATA }.second.decodeToString(),
        )
        assertEquals(storedJson, data.halls.single { it.name == "Saal A" }.layoutJson)

        // Into a fresh install, and back onto device A (whose safety copy then holds the format 3 layout).
        val deviceB = newDevice("b")
        deviceB.seed()
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
        val restored = RoomHallRepository(deviceB.db, Dispatchers.Default).getHall(userHall.id)!!
        assertEquals(userHall.layout, restored.layout)
        assertEquals(storedJson, deviceB.db.hallDao().get(userHall.id)!!.layoutJson)

        deviceA.repository.commitImport(deviceA.repository.prepareImport(doc))
        val copy = deviceA.latestCopyData()!!
        BackupValidator.validate(copy, copy.filePaths)
        assertEquals(storedJson, copy.halls.single { it.name == "Saal A" }.layoutJson)
    }

    @Test
    fun seatIdMarksSurviveTheRoundTrip() = runTest {
        buildRealisticData()
        val userHall = db.hallDao().getAll().single { it.name == "Saal A" }
        val before = halls.getHall(userHall.id)!!
        // The removed first row keeps its ids reserved.
        assertTrue(before.nextSeatId > before.layout!!.seatIds.maxOf { it.value } + 1)
        deviceA.repository.export(deviceA.document("b.zip"))

        val deviceB = newDevice("b")
        val preview = deviceB.repository.prepareImport(deviceA.document("b.zip"))
        deviceB.repository.commitImport(preview)

        val after = RoomHallRepository(deviceB.db, Dispatchers.Default).getHall(userHall.id)!!
        assertEquals(before.nextSeatId, after.nextSeatId)
        assertEquals(before.layout, after.layout)
        val nextSession = after.startLayoutEdit().newGrid(1, 1)
        assertEquals(before.nextSeatId, nextSession.layout!!.seatIds.single().value)
    }

    @Test
    fun importOnTheSameDeviceReplacesNewerChangesAndCleansUpFiles() = runTest {
        buildRealisticData()
        val expected = deviceA.snapshot()
        val expectedFiles = deviceA.files()
        deviceA.repository.export(deviceA.document("backup.zip"))

        // Changes after the export: a new visit with a photo, a renamed cinema.
        val hall = db.hallDao().getAll().first()
        visits.save(Visit(hallId = hall.id, date = LocalDate.of(2026, 10, 5), photoPath = deviceA.writeFile("photos/visit-new.jpg")))
        cinemas.renameCinema(db.cinemaDao().getAll().first().id, "Renamed")

        val preview = deviceA.repository.prepareImport(deviceA.document("backup.zip"))
        deviceA.repository.commitImport(preview)

        assertEquals(expected, deviceA.snapshot())
        // The photo of the newer visit was only referenced by the replaced data.
        assertEquals(expectedFiles, deviceA.files())
    }

    @Test
    fun aDifferentFileWithTheSameNameIsNeverOverwritten() = runTest {
        buildRealisticData()
        val expectedFiles = deviceA.files()
        deviceA.repository.export(deviceA.document("backup.zip"))

        val deviceB = newDevice("b")
        val strangerPhoto = byteArrayOf(1, 2, 3)
        deviceB.writeFile("photos/visit-1.jpg", strangerPhoto)
        val preview = deviceB.repository.prepareImport(deviceA.document("backup.zip"))
        deviceB.repository.commitImport(preview)

        val imported = deviceB.snapshot().visits.single { it.filmTitle == "Perfect Days" }.photoPath!!
        assertNotEquals("photos/visit-1.jpg", imported)
        assertEquals(expectedFiles["photos/visit-1.jpg"], deviceB.files()[imported])
        // The stranger file was not referenced by device B's old data, so it is left alone.
        assertTrue(File(deviceB.filesDir, "photos/visit-1.jpg").readBytes().contentEquals(strangerPhoto))
    }

    @Test
    fun exportRecordsTheLastExport() = runTest {
        buildRealisticData()
        assertNull(deviceA.repository.lastExport.first())
        val counts = deviceA.repository.export(deviceA.document("backup.zip"))
        assertEquals(LastExport(Instant.parse("2026-10-03T12:05:00Z"), counts), deviceA.repository.lastExport.first())
    }

    @Test
    fun exportWritesManifestFirstAndOnlyReferencedFiles() = runTest {
        buildRealisticData()
        deviceA.writeFile("photos/visit-orphan.jpg")
        val doc = deviceA.document("backup.zip")
        deviceA.repository.export(doc)

        val entries = readZip(File(doc))
        assertEquals(listOf("manifest.json", "data.json"), entries.take(2).map { it.first })
        assertEquals(
            listOf("photos/visit-1.jpg", "photos/visit-2.jpg").sorted() + deviceA.files().keys.filter { it.startsWith("reference/") }.sorted(),
            entries.drop(2).map { it.first }.sorted(),
        )
        val manifest = BackupFormat.manifestJson.decodeFromString(BackupManifest.serializer(), entries[0].second.decodeToString())
        assertEquals("cinexperience-backup", manifest.format)
        assertEquals(2, manifest.backupVersion)
        assertEquals("2026-10-03T12:05:00Z", manifest.exportedAt)
    }

    @Test
    fun aPhotoMissingOnDiskIsLeftOutInsteadOfBlockingTheExport() = runTest {
        buildRealisticData()
        File(deviceA.filesDir, "photos/visit-2.jpg").delete()
        val doc = deviceA.document("backup.zip")
        val counts = deviceA.repository.export(doc)
        assertEquals(1, counts.photos)

        val deviceB = newDevice("b")
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
        assertNull(deviceB.snapshot().visits.single { it.filmTitle == "Aftersun" }.photoPath)
    }

    @Test
    fun anEmptyUnseededBackupImportsAsAFreshInstall() = runTest {
        val doc = deviceA.document("empty.zip")
        assertEquals(BackupCounts(0, 0, 0, 0, 0, 0), deviceA.repository.export(doc))
        val deviceB = newDevice("b")
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
        // Seed version 0 in the backup: seeding after the import loads the shipped seed.
        deviceA.seed()
        assertEquals(deviceA.snapshot(), deviceB.snapshot())
    }

    @Test
    fun importOfAnOlderSeedStateIsUpgradedLikeAtAppStart() = runTest {
        // A backup from before any seeding (seed version 0): the import is followed by seeding.
        cinemas.createCinema("Kiezkino")
        val doc = deviceA.document("old.zip")
        deviceA.repository.export(doc)

        val deviceB = newDevice("b")
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
        val names = deviceB.snapshot().cinemas.map { it.name }
        assertEquals(14, names.size)
        assertTrue("Kiezkino" in names)
    }
}
