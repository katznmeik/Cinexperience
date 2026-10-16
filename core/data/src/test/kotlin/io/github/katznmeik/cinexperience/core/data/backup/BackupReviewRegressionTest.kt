package io.github.katznmeik.cinexperience.core.data.backup

import android.database.sqlite.SQLiteFullException
import io.github.katznmeik.cinexperience.core.data.repository.RoomCinemaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/** Regression tests for the issues found in the independent review of the backup engine. */
internal class BackupReviewRegressionTest : BackupTestBase() {

    // 1. A failed import or restore must never hide (and later prune) the real safety copy.

    /**
     * Device B imports A (the copy holds B's own data), then "Restore safety copy"
     * fails via [fail]; afterwards the copy of B's data must still be the newest
     * (and only) one, and a retry must bring B's data back.
     */
    private suspend fun failedRestoreKeepsTheRealCopy(dataStaysAsBefore: Boolean = true, fail: suspend (Device) -> ImportPreview) {
        val (deviceB, doc) = prepareTwoDevices()
        val bOnly = deviceB.snapshot()
        val bFiles = deviceB.files()
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
        val copies = deviceB.safetyCopyFiles()
        assertEquals(1, copies.size)
        assertEquals(bOnly, deviceB.latestCopyData())
        val copyTime = deviceB.repository.latestSafetyCopy.first()
        val imported = deviceB.snapshot()

        clock.now = clock.now.plus(Duration.ofMinutes(5))
        val preview = fail(deviceB)
        try {
            deviceB.repository.commitImport(preview)
            fail("Expected the restore to fail")
        } catch (e: BackupException) {
            // expected
        }
        assertEquals(copies, deviceB.safetyCopyFiles())
        assertEquals(bOnly, deviceB.latestCopyData())
        assertEquals(copyTime, deviceB.repository.latestSafetyCopy.first())
        if (dataStaysAsBefore) assertEquals(imported, deviceB.snapshot())
        assertFalse(deviceB.repository.isImporting.value)

        // The retry works and restores B's own data.
        deviceB.failpoint = {}
        clock.now = clock.now.plus(Duration.ofMinutes(5))
        deviceB.repository.commitImport(deviceB.repository.prepareSafetyCopyRestore())
        assertEquals(bOnly, deviceB.snapshot())
        assertEquals(bFiles, deviceB.files())
        assertEquals(2, deviceB.safetyCopyFiles().size)
    }

    @Test
    fun aRestoreFailingWhilePlacingFilesKeepsTheSafetyCopy() = runTest {
        failedRestoreKeepsTheRealCopy { device ->
            device.failpoint = { if (it == ImportStep.FILE_PLACED) throw IOException("Disk full") }
            device.repository.prepareSafetyCopyRestore()
        }
    }

    @Test
    fun aRestoreFailingInsideTheTransactionKeepsTheSafetyCopy() = runTest {
        failedRestoreKeepsTheRealCopy { device ->
            device.failpoint = { if (it == ImportStep.ROWS_REPLACED) throw SQLiteFullException("database or disk is full") }
            device.repository.prepareSafetyCopyRestore()
        }
    }

    @Test
    fun aRestoreWhoseStagedFilesWereClearedFailsAndKeepsTheSafetyCopy() = runTest {
        failedRestoreKeepsTheRealCopy { device ->
            device.repository.prepareSafetyCopyRestore().also {
                File(device.cacheDir, RoomBackupRepository.STAGING_FOLDER).deleteRecursively()
            }
        }
    }

    @Test
    fun dataEditedDuringARestoreFailsItAndKeepsTheSafetyCopy() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        val bOnly = deviceB.snapshot()
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
        val copies = deviceB.safetyCopyFiles()
        clock.now = clock.now.plus(Duration.ofMinutes(5))

        val restore = deviceB.repository.prepareSafetyCopyRestore()
        var edited = false
        try {
            deviceB.repository.commitImport(restore) { progress ->
                if (!edited && progress > 0f && progress < 0.5f) {
                    edited = true
                    runBlocking { RoomCinemaRepository(deviceB.db, Dispatchers.Default).createCinema("Meanwhile") }
                }
            }
            fail("Expected the restore to fail")
        } catch (e: BackupException) {
            assertEquals(BackupError.DATA_CHANGED, e.error)
        }
        assertEquals(copies, deviceB.safetyCopyFiles())
        assertEquals(bOnly, deviceB.latestCopyData())

        deviceB.repository.commitImport(deviceB.repository.prepareSafetyCopyRestore())
        assertEquals(bOnly, deviceB.snapshot())
    }

    @Test
    fun aFailedImportFromAFileLeavesNoSafetyCopy() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        deviceB.failpoint = { if (it == ImportStep.ROWS_REPLACED) throw SQLiteFullException("full") }
        repeat(3) {
            clock.now = clock.now.plusSeconds(60)
            try {
                deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
                fail("Expected the import to fail")
            } catch (e: BackupException) {
                assertEquals(BackupError.WRITE_FAILED, e.error)
            }
        }
        assertTrue(deviceB.safetyCopyFiles().isEmpty())
        assertNull(deviceB.repository.latestSafetyCopy.first())
    }

    // 1b. Anything that fails after the commit must not undo the safety copy or report a failure.

    /**
     * Device B imports A while something fails after the database commit; the
     * import must count as done: A's data live, B's data in the safety copy, success
     * reported, and the copy restorable.
     */
    private suspend fun failureAfterTheCommitIsStillASuccess(onProgress: (Float) -> Unit = {}, setup: (Device) -> Unit = {}) {
        val (deviceB, doc) = prepareTwoDevices()
        val bOnly = deviceB.snapshot()
        val bFiles = deviceB.files()
        setup(deviceB)
        val counts = deviceB.repository.commitImport(deviceB.repository.prepareImport(doc), onProgress)

        assertEquals(deviceA.snapshot().counts(), counts)
        assertEquals(deviceA.snapshot(), deviceB.snapshot())
        assertEquals(1, deviceB.safetyCopyFiles().size)
        assertEquals(bOnly, deviceB.latestCopyData())
        assertFalse(deviceB.repository.isImporting.value)
        assertTrue(deviceB.stagingIsEmpty())

        deviceB.failpoint = {}
        clock.now = clock.now.plusSeconds(60)
        deviceB.repository.commitImport(deviceB.repository.prepareSafetyCopyRestore())
        assertEquals(bOnly, deviceB.snapshot())
        assertEquals(bFiles, deviceB.files())
    }

    @Test
    fun aProgressCallbackThrowingAfterTheCommitStillCountsAsSuccess() = runTest {
        failureAfterTheCommitIsStillASuccess(onProgress = { if (it >= 1f) throw IllegalStateException("The screen is gone") })
    }

    @Test
    fun anErrorRightAfterTheCommitStillCountsAsSuccess() = runTest {
        failureAfterTheCommitIsStillASuccess { device ->
            device.failpoint = { if (it == ImportStep.COMMITTED) throw OutOfMemoryError("Simulated") }
        }
    }

    @Test
    fun anErrorWhileDeletingOldFilesStillCountsAsSuccess() = runTest {
        var deleted = 0
        failureAfterTheCommitIsStillASuccess { device ->
            device.failpoint = {
                if (it == ImportStep.OLD_FILE_DELETED) {
                    deleted++
                    throw StackOverflowError()
                }
            }
        }
        assertEquals(1, deleted)
    }

    @Test
    fun anErrorBeforeTheCommitIsReportedAndDropsTheNewCopy() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        val before = deviceB.snapshot()
        val files = deviceB.files()
        deviceB.failpoint = { if (it == ImportStep.ROWS_REPLACED) throw OutOfMemoryError("Simulated") }
        try {
            deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
            fail("Expected the import to fail")
        } catch (e: BackupException) {
            assertEquals(BackupError.WRITE_FAILED, e.error)
        }
        assertEquals(before, deviceB.snapshot())
        assertEquals(files, deviceB.files())
        assertTrue(deviceB.safetyCopyFiles().isEmpty())
        assertFalse(deviceB.repository.isImporting.value)
    }

    // 2. A clock that goes backwards must not prune the copy just written.

    @Test
    fun clockGoingBackwardsKeepsTheNewestCopy() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        val cinemasB = RoomCinemaRepository(deviceB.db, Dispatchers.Default)
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc)) // copy 1 at 12:05

        clock.now = clock.now.minus(Duration.ofHours(1))
        cinemasB.createCinema("Later")
        val beforeSecond = deviceB.snapshot()
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc)) // copy 2 at 11:05
        assertEquals(beforeSecond, deviceB.latestCopyData())
        assertEquals(Instant.parse("2026-10-03T11:05:00Z"), deviceB.repository.latestSafetyCopy.first()!!.createdAt)

        clock.now = clock.now.minus(Duration.ofHours(1))
        cinemasB.createCinema("Even later")
        val beforeThird = deviceB.snapshot()
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc)) // copy 3 at 10:05
        assertEquals(beforeThird, deviceB.latestCopyData())
        assertEquals(
            listOf("pre-import-2-20261003T110500000Z.zip", "pre-import-3-20261003T100500000Z.zip"),
            deviceB.safetyCopyFiles(),
        )
        deviceB.repository.commitImport(deviceB.repository.prepareSafetyCopyRestore())
        assertEquals(beforeThird, deviceB.snapshot())
    }

    // 5. Exports and safety copies must never produce a backup that can't be imported.

    private suspend fun breakALayout(device: Device): String {
        val hall = device.db.hallDao().getAll().first { it.layoutJson != null }
        device.db.hallDao().update(hall.copy(layoutJson = """{"formatVersion":99,"rows":[]}"""))
        return hall.name
    }

    @Test
    fun exportOfDataThatCantBeImportedFailsAndNamesTheProblem() = runTest {
        buildRealisticData()
        val hallName = breakALayout(deviceA)
        val doc = deviceA.document("backup.zip")
        try {
            deviceA.repository.export(doc)
            fail("Expected the export to fail")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_DATA, e.error)
            assertTrue(e.message!!, e.message!!.startsWith("Your data can't be backed up: the seat layout of hall “$hallName”"))
        }
        assertFalse(File(doc).exists())
        assertNull(deviceA.repository.lastExport.first())
    }

    @Test
    fun exportAndSafetyCopiesCheckTheScreenOffset() = runTest {
        // The repositories never store such an offset; a damaged database could.
        val (deviceB, doc) = prepareTwoDevices()
        val hall = deviceB.db.hallDao().getAll().first()
        deviceB.db.hallDao().update(hall.copy(screenOffset = 0.3f))
        try {
            deviceB.repository.export(deviceB.document("b.zip"))
            fail("Expected the export to fail")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_DATA, e.error)
            assertTrue(e.message!!, e.message!!.startsWith("Your data can't be backed up: hall “${hall.name}” has an invalid screen position"))
        }
        try {
            deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
            fail("Expected the import to fail")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_DATA, e.error)
            assertTrue(e.message!!, e.message!!.startsWith("Current data can't be backed up: hall “${hall.name}” has an invalid screen position"))
        }
        assertTrue(deviceB.safetyCopyFiles().isEmpty())
    }

    @Test
    fun importIsCancelledWhenTheCurrentDataCantBeBackedUp() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        val hallName = breakALayout(deviceB)
        val before = deviceB.snapshot()
        val files = deviceB.files()
        try {
            deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
            fail("Expected the import to fail")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_DATA, e.error)
            assertTrue(e.message!!, e.message!!.startsWith("Current data can't be backed up: the seat layout of hall “$hallName”"))
            assertTrue(e.message!!, e.message!!.endsWith(" — import cancelled."))
        }
        assertEquals(before, deviceB.snapshot())
        assertEquals(files, deviceB.files())
        assertTrue(deviceB.safetyCopyFiles().isEmpty())
    }

    // 6. Hardening.

    @Test
    fun leftoverStagingIsClearedAtStartButAPreparedImportIsKept() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        val leftover = File(deviceB.cacheDir, "${RoomBackupRepository.STAGING_FOLDER}/old/files/photos/x.jpg")
        leftover.parentFile!!.mkdirs()
        leftover.writeText("left by a killed process")
        deviceB.repository.clearLeftoverStaging()
        assertTrue(deviceB.stagingIsEmpty())

        val preview = deviceB.repository.prepareImport(doc)
        deviceB.repository.clearLeftoverStaging()
        assertFalse(deviceB.stagingIsEmpty())
        deviceB.repository.commitImport(preview)
        assertEquals(deviceA.snapshot(), deviceB.snapshot())
    }

    @Test
    fun largeFilesAreComparedCompletely() = runTest {
        buildRealisticData()
        val big = Random.nextBytes(300_000)
        deviceA.writeFile("photos/visit-1.jpg", big)
        val doc = deviceA.document("backup.zip")
        deviceA.repository.export(doc)

        // Same name, same size, different last byte: must not be taken for the same file.
        val deviceB = newDevice("b")
        deviceB.writeFile("photos/visit-1.jpg", big.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
        val path = deviceB.snapshot().visits.single { it.filmTitle == "Perfect Days" }.photoPath!!
        assertTrue(path != "photos/visit-1.jpg")
        assertTrue(File(deviceB.filesDir, path).readBytes().contentEquals(big))

        // Identical bytes: reused under the same name.
        val deviceC = newDevice("c")
        deviceC.writeFile("photos/visit-1.jpg", big)
        deviceC.repository.commitImport(deviceC.repository.prepareImport(doc))
        assertEquals("photos/visit-1.jpg", deviceC.snapshot().visits.single { it.filmTitle == "Perfect Days" }.photoPath)
    }
}
