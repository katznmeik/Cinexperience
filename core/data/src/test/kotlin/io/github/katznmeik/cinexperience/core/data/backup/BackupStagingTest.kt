package io.github.katznmeik.cinexperience.core.data.backup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Unpacking a backup into the cache: folder entries can't carry data past the
 * size limits. A refused backup leaves the data, files and safety copies untouched.
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
}
