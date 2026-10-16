package io.github.katznmeik.cinexperience.core.data.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.DataContainer
import io.github.katznmeik.cinexperience.core.data.files.FileReferenceImageStore
import io.github.katznmeik.cinexperience.core.data.files.FileVisitPhotoStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration

/**
 * An import gives every file it moves into place, and every existing file it
 * reuses (same name, same bytes), a fresh date (BackupRestorer). Until the
 * commit the database doesn't refer to them yet, so without that date the
 * startup sweep, which deletes unreferenced files older than a minimum age,
 * could delete an old reused file while the import is still running.
 */
internal class BackupFileDatesTest : BackupTestBase() {

    /**
     * The startup sweep of [DataContainer.sweepOrphanFiles] on [device]'s folders:
     * the same stores and minimum ages, with the file references of [data].
     */
    private suspend fun sweep(device: Device, data: BackupData, now: Long): Int {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir(): File = device.filesDir

            override fun getCacheDir(): File = device.cacheDir
        }
        return FileReferenceImageStore(context).deleteOrphans(data.referenceImagePaths, now - DataContainer.ORPHAN_MIN_AGE_MS) +
            FileVisitPhotoStore(context).deleteOrphans(data.photoPaths, now - DataContainer.VISIT_PHOTO_MIN_AGE_MS)
    }

    @Test
    fun importedAndReusedFilesGetAFreshDateAndSurviveASweepBeforeTheCommit() = runTest {
        buildRealisticData()
        val doc = deviceA.document("backup.zip")
        deviceA.repository.export(doc)
        val exported = deviceA.files().keys
        val referencePath = exported.first { it.startsWith("reference/") }

        // Device B already has a photo and a reference image of the backup, unreferenced for a month.
        val deviceB = newDevice("b")
        val monthAgo = clock.instant().minus(Duration.ofDays(30)).toEpochMilli()
        for (path in listOf("photos/visit-1.jpg", referencePath)) {
            deviceB.writeFile(path, File(deviceA.filesDir, path).readBytes())
            assertTrue(File(deviceB.filesDir, path).setLastModified(monthAgo))
        }
        val oldData = deviceB.snapshot()

        // The sweep runs in the last moment before the commit, when the database still holds the old data.
        var swept = -1
        deviceB.failpoint = { step ->
            if (step == ImportStep.ROWS_REPLACED) swept = runBlocking { sweep(deviceB, oldData, clock.millis()) }
        }
        deviceB.repository.commitImport(deviceB.repository.prepareImport(doc))

        assertEquals(0, swept)
        assertEquals(deviceA.files(), deviceB.files())
        // The reused files (same names, nothing renamed) and the moved ones all carry the import's date.
        assertEquals(exported, deviceB.snapshot().filePaths)
        for (path in exported) {
            assertEquals(path, clock.millis(), File(deviceB.filesDir, path).lastModified())
        }
    }
}
