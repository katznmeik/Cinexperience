package io.github.katznmeik.cinexperience.core.data.backup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Unpacking a backup into the cache: folder entries can't carry data past the
 * size limits, and no file is staged without room for it on the device. A
 * refused backup leaves the data, files and safety copies untouched.
 */
internal class BackupStagingTest : BackupTestBase() {

    private lateinit var valid: File

    private suspend fun exportValid() {
        buildRealisticData()
        valid = File(deviceA.document("valid.zip"))
        deviceA.repository.export(valid.path)
    }

    private fun modified(name: String, change: MutableList<Pair<String, ByteArray>>.() -> Unit): String {
        val entries = readZip(valid)
        entries.change()
        return File(deviceA.root, name).apply { writeBytes(zipBytes(entries)) }.path
    }

    private suspend fun assertRefused(path: String, error: BackupError, device: Device = deviceA): BackupException {
        val before = device.snapshot()
        val filesBefore = device.files()
        val copiesBefore = device.safetyCopyFiles()
        val exception = try {
            device.repository.prepareImport(path)
            fail("Expected the backup to be refused")
            error("unreachable")
        } catch (e: BackupException) {
            e
        }
        assertEquals(exception.message, error, exception.error)
        assertEquals(before, device.snapshot())
        assertEquals(filesBefore, device.files())
        assertEquals(copiesBefore, device.safetyCopyFiles())
        assertTrue(device.stagingIsEmpty())
        return exception
    }

    @Test
    fun aFolderEntryWithContentIsTooLarge() = runTest {
        exportValid()
        // A folder entry holds no data; anything in one would be inflated outside every limit.
        val zeros = modified("folder-zeros.zip") { add(2, "photos/" to ByteArray(1_000_000)) }
        assertRefused(zeros, BackupError.TOO_LARGE)
        val oneByte = modified("folder-byte.zip") { add(2, "reference/" to byteArrayOf(1)) }
        assertRefused(oneByte, BackupError.TOO_LARGE)
    }

    @Test
    fun noFileIsStagedWithoutRoomForIt() = runTest {
        exportValid()
        // A reserve larger than any disk: as if the device were full.
        val full = newDevice("full", BackupLimits(minFreeBytes = Long.MAX_VALUE / 4))
        val e = assertRefused(valid.path, BackupError.WRITE_FAILED, device = full)
        assertTrue(e.message, e.message!!.contains("enough free storage"))

        // The check comes before each photo or reference image: a backup without any still imports.
        val noFiles = deviceA.document("no-files.zip")
        newDevice("empty").repository.export(noFiles)
        val preview = full.repository.prepareImport(noFiles)
        full.repository.commitImport(preview)
        assertTrue(full.files().isEmpty())
    }
}
