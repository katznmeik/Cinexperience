package io.github.katznmeik.cinexperience.core.data.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Writing the safety copy before an import replaces the data: it is written only
 * if all of it fits with [BackupLimits.minFreeBytes] to spare, and a file deleted
 * while it is written stops the import as a change of the data. Either way
 * nothing is replaced and no copy is left behind.
 */
internal class BackupSafetyCopyTest : BackupTestBase() {

    /** A backup repository on this device whose files folder has [free] bytes of free storage. */
    private fun Device.repositoryWithRoom(free: () -> Long) = RoomBackupRepository(
        db = db,
        filesDir = filesDir,
        cacheDir = cacheDir,
        documents = documents,
        lastExportStore = lastExports,
        appVersion = { "1.2.3" },
        ioDispatcher = Dispatchers.IO,
        clock = clock,
        // The staging folder in the cache always has room.
        freeSpace = { dir -> if (dir.startsWith(filesDir)) free() else Long.MAX_VALUE / 4 },
    )

    private suspend fun failedImport(import: suspend () -> Unit): BackupException = try {
        import()
        fail("Expected the import to fail")
        error("unreachable")
    } catch (e: BackupException) {
        e
    }

    @Test
    fun aSafetyCopyIsWrittenOnlyWithRoomForAllOfItAndTheReserve() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        val before = deviceB.snapshot()
        val filesBefore = deviceB.files()
        val writer = BackupWriter(deviceB.filesDir)
        val size = writer.maxSize(writer.prepare(before))
        val needed = size + BackupLimits().minFreeBytes
        var free = needed - 1
        val repository = deviceB.repositoryWithRoom { free }

        val e = failedImport { repository.commitImport(repository.prepareImport(doc)) }
        assertEquals(e.message, BackupError.WRITE_FAILED, e.error)
        assertEquals(
            "There isn't enough free storage for a safety copy of your current data, so nothing was imported. " +
                "Free up some space and try again.",
            e.message,
        )
        assertEquals(before, deviceB.snapshot())
        assertEquals(filesBefore, deviceB.files())
        // Stopped before writing: not even a temporary file.
        assertEquals(emptyList<String>(), deviceB.safetyCopyFiles())
        assertTrue(deviceB.stagingIsEmpty())

        // Just enough room: the copy is written, and it is no larger than reckoned.
        free = needed
        repository.commitImport(repository.prepareImport(doc))
        assertEquals(deviceA.snapshot(), deviceB.snapshot())
        val copy = File(File(deviceB.filesDir, SafetyCopies.FOLDER), deviceB.safetyCopyFiles().single())
        assertTrue("${copy.length()} > $size", copy.length() <= size)
    }

    @Test
    fun aFileDeletedWhileTheSafetyCopyIsWrittenIsAChangeOfTheData() = runTest {
        val (deviceB, doc) = prepareTwoDevices()
        val before = deviceB.snapshot()
        val photo = File(deviceB.filesDir, "photos/visit-b.jpg")
        assertTrue(photo.isFile)

        val e = failedImport {
            // The safety copy reports the first half of the progress; its photo comes after the manifest.
            deviceB.repository.commitImport(deviceB.repository.prepareImport(doc)) { progress ->
                if (progress > 0f) photo.delete()
            }
        }
        assertEquals(e.message, BackupError.DATA_CHANGED, e.error)
        assertEquals("Your data changed while importing. Nothing was replaced; please try again.", e.message)
        assertEquals(before, deviceB.snapshot())
        assertEquals(emptyList<String>(), deviceB.safetyCopyFiles())
        assertTrue(deviceB.stagingIsEmpty())
    }
}
