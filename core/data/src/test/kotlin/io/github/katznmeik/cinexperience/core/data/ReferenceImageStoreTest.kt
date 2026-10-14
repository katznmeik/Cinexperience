package io.github.katznmeik.cinexperience.core.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.files.FileReferenceImageStore
import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ReferenceImageStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = FileReferenceImageStore(context, Dispatchers.Unconfined)
    private val folder = File(context.filesDir, ReferenceImageStore.FOLDER)

    private fun pngFile(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        return File(context.cacheDir, "source-$width-$height.png").also { file ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun storedFile(name: String, ageMs: Long): File = File(folder.apply { mkdirs() }, name).also {
        it.writeText("x")
        it.setLastModified(System.currentTimeMillis() - ageMs)
    }

    @Test
    fun importCopiesAndDownscalesIntoTheReferenceFolder() = runTest {
        val path = store.importImage(7, Uri.fromFile(pngFile(3000, 1500)).toString())
        assertTrue(path, path.matches(Regex("reference/hall-7-[0-9a-f-]{36}\\.jpg")))
        assertTrue(File(context.filesDir, path).isFile)
        val loaded = store.loadImage(path)
        assertNotNull(loaded)
        assertEquals(ReferenceImageStore.MAX_DIMENSION, loaded!!.width)
        assertEquals(ReferenceImageStore.MAX_DIMENSION / 2, loaded.height)
        assertFalse(folder.listFiles().orEmpty().any { it.name.endsWith(".tmp") })

        val small = store.importImage(7, Uri.fromFile(pngFile(40, 30)).toString())
        assertEquals(40, store.loadImage(small)!!.width)

        store.delete(path)
        assertFalse(File(context.filesDir, path).exists())
        assertNull(store.loadImage(path))
    }

    private suspend fun assertImportFails(file: File) {
        try {
            store.importImage(1, Uri.fromFile(file).toString())
            fail("Importing $file should fail")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun unreadableSourcesFailWithIOException() = runTest {
        assertImportFails(File(context.cacheDir, "notes.txt").apply { writeText("hello") })
        assertImportFails(File(context.cacheDir, "missing.png"))
        assertTrue(folder.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun pathsOutsideTheFolderAreIgnored() = runTest {
        val outside = File(context.filesDir, "important.txt").apply { writeText("keep") }
        store.delete("important.txt")
        store.delete("reference/../important.txt")
        assertTrue(outside.exists())
        assertNull(store.loadImage("../important.txt"))
    }

    @Test
    fun sweepDeletesOnlyOldUnreferencedFiles() = runTest {
        val referenced = storedFile("hall-1-a.jpg", ageMs = DAY)
        val orphan = storedFile("hall-1-b.jpg", ageMs = DAY)
        val leftoverTemp = storedFile("hall-1-c.jpg.tmp", ageMs = DAY)
        val fresh = storedFile("hall-2-d.jpg", ageMs = 1_000)

        val deleted = store.deleteOrphans(setOf("reference/hall-1-a.jpg"), modifiedBefore = System.currentTimeMillis() - HOUR)

        assertEquals(2, deleted)
        assertTrue(referenced.exists())
        assertFalse(orphan.exists())
        assertFalse(leftoverTemp.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun dataContainerSweepUsesTheHallsReferences() = runTest {
        val data = DataContainer(context, backgroundScope, Dispatchers.Default, Dispatchers.IO)
        val keep = storedFile("hall-x-keep.jpg", ageMs = DAY)
        val orphan = storedFile("hall-x-orphan.jpg", ageMs = DAY)
        val halls = data.hallRepository
        val hallId = halls.createHall(data.cinemaRepository.createCinema("Kino"), "Saal 1")
        halls.updateHall(halls.getHall(hallId)!!.copy(referenceImagePath = "reference/hall-x-keep.jpg"))

        assertEquals(1, data.sweepOrphanReferenceImages())
        assertTrue(keep.exists())
        assertFalse(orphan.exists())
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
    }
}
