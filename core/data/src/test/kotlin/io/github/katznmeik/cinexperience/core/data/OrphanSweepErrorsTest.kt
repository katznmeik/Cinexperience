package io.github.katznmeik.cinexperience.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.backup.RoomBackupRepository
import io.github.katznmeik.cinexperience.core.data.files.FileVisitPhotoStore
import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import io.github.katznmeik.cinexperience.core.data.files.VisitPhotoStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The startup sweep ([DataContainer.sweepOrphanFiles]) never throws: a step that
 * fails is logged and counts no deleted files, and the other steps still run.
 * When the references can't be read, nothing is deleted, however old.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OrphanSweepErrorsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val photos = File(context.filesDir, VisitPhotoStore.FOLDER)
    private val references = File(context.filesDir, ReferenceImageStore.FOLDER)
    private val captures = File(context.cacheDir, FileVisitPhotoStore.CAPTURE_FOLDER)
    private val staging = File(context.cacheDir, RoomBackupRepository.STAGING_FOLDER)
    private val now = System.currentTimeMillis()

    private fun file(dir: File, name: String, ageMs: Long): File = File(dir.apply { mkdirs() }, name).also {
        it.writeText("x")
        it.setLastModified(now - ageMs)
    }

    private fun data(scope: CoroutineScope) = DataContainer(context, scope, "1.2.3", Dispatchers.Default, Dispatchers.IO)

    @Test
    fun aDatabaseThatCantBeOpenedKeepsEveryFile() = runTest {
        // A folder where the database file belongs: opening the database fails.
        assertTrue(context.getDatabasePath(DatabaseConfig.DATABASE_NAME).mkdirs())
        val photo = file(photos, "visit-old.jpg", ageMs = 30 * DAY)
        val reference = file(references, "hall-1-old.jpg", ageMs = 30 * DAY)
        val leftover = file(File(staging, "earlier-import"), "data.json", ageMs = 0)

        assertEquals(0, data(backgroundScope).sweepOrphanFiles(now))
        // Which files are referenced is unknown, so none is deleted.
        assertTrue(photo.exists())
        assertTrue(reference.exists())
        // The import staging doesn't need the database: it is still cleared.
        assertFalse(leftover.exists())
    }

    @Test
    fun aPhotoFolderThatCantBeListedDoesNotStopTheRest() = runTest {
        // A file where the photo folder belongs: it can't be listed.
        photos.parentFile!!.mkdirs()
        photos.writeText("not a folder")
        val reference = file(references, "hall-1-old.jpg", ageMs = 2 * HOUR)
        val capture = file(captures, "capture-old.jpg", ageMs = 8 * DAY)

        assertEquals(2, data(backgroundScope).sweepOrphanFiles(now))
        assertFalse(reference.exists())
        assertFalse(capture.exists())
        assertTrue(photos.isFile)
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
    }
}
