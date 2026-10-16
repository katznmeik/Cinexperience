package io.github.katznmeik.cinexperience.core.data.backup

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The tabs stay usable while an export runs, so a photo or reference image can
 * be deleted (e.g. a visit saved with another photo) after the export read the
 * data. data.json, which comes first, already refers to the file, and an import
 * refuses a backup that lacks a referenced file. So the export fails with a
 * message that says what happened, and trying again writes a complete backup.
 */
internal class BackupExportDeletedFileTest : BackupTestBase() {

    @Test
    fun aFileDeletedDuringTheExportFailsItWithAClearMessage() = runTest {
        buildRealisticData()
        val doc = deviceA.document("backup.zip")
        val photo = File(deviceA.filesDir, "photos/visit-2.jpg")
        assertTrue(photo.isFile)

        val e = try {
            // Progress is reported after each entry: the manifest is written, the photo's entry is still to come.
            deviceA.repository.export(doc) { progress -> if (progress > 0f) photo.delete() }
            fail("Expected the export to fail")
            error("unreachable")
        } catch (e: BackupException) {
            e
        }
        assertEquals(e.message, BackupError.DATA_CHANGED, e.error)
        assertEquals(
            "A photo or reference image was changed while the backup was being written. Nothing was exported; please try again.",
            e.message,
        )
        // No partial backup is left behind, and none is recorded.
        assertTrue(doc in deviceA.documents.deleted)
        assertFalse(File(doc).exists())
        assertNull(deviceA.repository.lastExport.first())

        // Trying again writes a backup that imports; the visit whose photo is gone has none.
        val counts = deviceA.repository.export(doc)
        assertEquals(1, counts.photos)
        val deviceB = newDevice("b")
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))
        assertNull(deviceB.snapshot().visits.single { it.filmTitle == "Aftersun" }.photoPath)
    }
}
