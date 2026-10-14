package io.github.katznmeik.cinexperience.core.data.backup

import io.github.katznmeik.cinexperience.core.data.repository.RoomVisitRepository
import io.github.katznmeik.cinexperience.core.data.save
import io.github.katznmeik.cinexperience.core.model.Visit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

internal class BackupAtomicityTest : BackupTestBase() {

    /** Device B with its own data (a seeded install with one visit and photo) and a backup of device A. */
    private suspend fun prepareTwoDevices(): Pair<Device, String> {
        buildRealisticData()
        val doc = deviceA.document("backup.zip")
        deviceA.repository.export(doc)
        val deviceB = newDevice("b")
        deviceB.seed()
        val hallId = deviceB.db.hallDao().getAll().first().id
        RoomVisitRepository(deviceB.db, Dispatchers.Default).save(
            Visit(hallId = hallId, date = LocalDate.of(2026, 1, 1), photoPath = deviceB.writeFile("photos/visit-b.jpg")),
        )
        return deviceB to doc
    }

    private suspend fun assertImportFailsAndChangesNothing(device: Device, doc: String, error: BackupError = BackupError.WRITE_FAILED) {
        val before = device.snapshot()
        val filesBefore = device.files()
        val preview = device.repository.prepareImport(doc)
        try {
            device.repository.commitImport(preview)
            fail("Expected the import to fail")
        } catch (e: BackupException) {
            assertEquals(error, e.error)
        }
        assertEquals(before, device.snapshot())
        assertEquals(filesBefore, device.files())
        assertTrue(device.stagingIsEmpty())
    }

    @Test
    fun failureInsideTheTransactionRollsBackRowsAndAddedFiles() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        deviceB.failpoint = { if (it == ImportStep.ROWS_REPLACED) throw IllegalStateException("Simulated crash") }
        assertImportFailsAndChangesNothing(deviceB, doc)
    }

    @Test
    fun failureWhileMovingFilesRemovesTheAddedOnesAndKeepsTheOldOnes() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        var placed = 0
        deviceB.failpoint = { if (it == ImportStep.FILE_PLACED && ++placed == 2) throw IOException("Disk full") }
        assertImportFailsAndChangesNothing(deviceB, doc)
        assertEquals(2, placed)
    }

    @Test
    fun failureAfterTheCommitStillCompletesTheImport() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        deviceB.failpoint = { if (it == ImportStep.COMMITTED) throw IllegalStateException("Simulated clean-up failure") }
        val expected = deviceA.snapshot()
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
        assertEquals(expected, deviceB.snapshot())
        // The old photo was not cleaned up; the startup sweep removes it later.
        assertTrue(File(deviceB.filesDir, "photos/visit-b.jpg").exists())
    }

    @Test
    fun aFailedImportCanBeRetriedFromTheFile() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        deviceB.failpoint = { if (it == ImportStep.ROWS_REPLACED) throw IllegalStateException("Simulated crash") }
        assertImportFailsAndChangesNothing(deviceB, doc)
        deviceB.failpoint = {}
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
        assertEquals(deviceA.snapshot(), deviceB.snapshot())
        assertEquals(deviceA.files(), deviceB.files())
    }

    @Test
    fun dataChangedDuringTheSafetyCopyAbortsTheImport() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        val preview = deviceB.repository.prepareImport(doc)
        var changed = false
        try {
            deviceB.repository.commitImport(preview) { progress ->
                if (!changed && progress > 0f && progress < 0.5f) {
                    changed = true
                    runBlocking { deviceB.db.cinemaDao().getAll().first().let { deviceB.db.cinemaDao().update(it.copy(name = "Edited meanwhile")) } }
                }
            }
            fail("Expected the import to fail")
        } catch (e: BackupException) {
            assertEquals(BackupError.DATA_CHANGED, e.error)
        }
        assertTrue(deviceB.snapshot().cinemas.any { it.name == "Edited meanwhile" })
        assertTrue(deviceB.files().keys.contains("photos/visit-b.jpg"))
    }

    @Test
    fun aStalePreviewCannotBeCommitted() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        val first = deviceB.repository.prepareImport(doc)
        deviceB.repository.prepareImport(doc) // replaces the first
        val before = deviceB.snapshot()
        try {
            deviceB.repository.commitImport(first)
            fail("Expected the import to fail")
        } catch (e: BackupException) {
            assertEquals(BackupError.IMPORT_EXPIRED, e.error)
        }
        assertEquals(before, deviceB.snapshot())
    }

    @Test
    fun clearedStagingFilesExpireThePreview() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        val preview = deviceB.repository.prepareImport(doc)
        File(deviceB.cacheDir, RoomBackupRepository.STAGING_FOLDER).deleteRecursively() // the system cleared the cache
        val before = deviceB.snapshot()
        try {
            deviceB.repository.commitImport(preview)
            fail("Expected the import to fail")
        } catch (e: BackupException) {
            assertEquals(BackupError.IMPORT_EXPIRED, e.error)
        }
        assertEquals(before, deviceB.snapshot())
    }

    @Test
    fun discardDropsTheStagedFiles() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        val preview = deviceB.repository.prepareImport(doc)
        assertTrue(!deviceB.stagingIsEmpty())
        deviceB.repository.discardImport(preview)
        assertTrue(deviceB.stagingIsEmpty())
    }

    @Test
    fun safetyCopiesAreWrittenBeforeEachImportAndPrunedToTwo() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        val original = deviceB.snapshot()
        val originalFiles = deviceB.files()
        assertNull(deviceB.repository.latestSafetyCopy.first())

        repeat(3) {
            clock.now = clock.now.plus(Duration.ofMinutes(10))
            deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
        }
        val copies = deviceB.safetyCopyFiles()
        assertEquals(2, copies.size)
        assertTrue(copies.all { it.startsWith("pre-import-") && it.endsWith(".zip") })
        assertEquals(SafetyCopy(clock.now), deviceB.repository.latestSafetyCopy.first())
        assertEquals(listOf("pre-import-20261003T122500000Z.zip", "pre-import-20261003T123500000Z.zip"), copies)
        // The first copy (device B's own data) was pruned; the remaining ones hold device A's data.
        val restore = deviceB.repository.prepareSafetyCopyRestore()
        assertEquals(ImportSource.SAFETY_COPY, restore.source)
        assertEquals(deviceA.snapshot().counts(), restore.counts)
        assertNotEquals(original.counts(), restore.counts)
        assertTrue(originalFiles.isNotEmpty())
    }

    @Test
    fun theSafetyCopyRestoresTheDataFromBeforeTheImport() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        val original = deviceB.snapshot()
        val originalFiles = deviceB.files()
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
        assertEquals(deviceA.snapshot(), deviceB.snapshot())

        clock.now = clock.now.plusSeconds(60)
        val restore = deviceB.repository.prepareSafetyCopyRestore()
        assertEquals(Instant.parse("2026-10-03T12:05:00Z"), restore.exportedAt)
        deviceB.repository.commitImport(restore)
        assertEquals(original, deviceB.snapshot())
        assertEquals(originalFiles, deviceB.files())
        // Restoring made a safety copy of the imported data in turn.
        assertEquals(2, deviceB.safetyCopyFiles().size)
    }

    @Test
    fun withoutASafetyCopyThereIsNothingToRestore() = runTest {
        try {
            deviceA.repository.prepareSafetyCopyRestore()
            fail("Expected an error")
        } catch (e: BackupException) {
            assertEquals(BackupError.NO_SAFETY_COPY, e.error)
        }
    }

    @Test
    fun failedSafetyCopyAbortsTheImport() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        // A file where the backups folder should be: the safety copy can't be written.
        File(deviceB.filesDir, SafetyCopies.FOLDER).writeText("blocker")
        assertImportFailsAndChangesNothing(deviceB, doc)
    }

    @Test
    fun failedExportIsNotRecordedAndTheDocumentIsDeleted() = runTest {
        buildRealisticData()
        val doc = deviceA.document("fail.zip")
        deviceA.documents.wrapOutput = { FailingOutputStream(it, limit = 4_000) }
        try {
            deviceA.repository.export(doc)
            fail("Expected the export to fail")
        } catch (e: BackupException) {
            assertEquals(BackupError.WRITE_FAILED, e.error)
        }
        assertEquals(listOf(doc), deviceA.documents.deleted)
        assertTrue(!File(doc).exists())
        assertNull(deviceA.repository.lastExport.first())
    }

    @Test
    fun unwritableDestinationIsAnError() = runTest {
        val doc = File(deviceA.root, "missing-folder/backup.zip").path
        try {
            deviceA.repository.export(doc)
            fail("Expected the export to fail")
        } catch (e: BackupException) {
            assertEquals(BackupError.WRITE_FAILED, e.error)
        }
        assertNull(deviceA.repository.lastExport.first())
    }

    @Test
    fun visitsAddedOnTheSameDeviceAfterAnImportGetFreshIds() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        val maxBefore = deviceB.snapshot().visits.maxOf { it.id }
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
        val hallId = deviceB.snapshot().halls.first().id
        val id = RoomVisitRepository(deviceB.db, Dispatchers.Default).save(Visit(hallId = hallId, date = LocalDate.of(2026, 10, 10)))
        assertTrue(id > deviceB.snapshot().visits.filter { it.id != id }.maxOf { it.id })
        assertTrue(id > maxBefore)
    }
}
