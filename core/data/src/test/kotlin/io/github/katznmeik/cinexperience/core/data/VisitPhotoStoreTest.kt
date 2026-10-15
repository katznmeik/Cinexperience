package io.github.katznmeik.cinexperience.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.files.FileVisitPhotoStore
import io.github.katznmeik.cinexperience.core.data.files.VisitPhotoStore
import io.github.katznmeik.cinexperience.core.model.Visit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.time.LocalDate
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class VisitPhotoStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = FileVisitPhotoStore(context, Dispatchers.Unconfined)
    private val folder = File(context.filesDir, VisitPhotoStore.FOLDER)
    private val captures = File(context.cacheDir, FileVisitPhotoStore.CAPTURE_FOLDER)

    /** A [width] × [height] JPEG: dark, with a light top-left quadrant to check rotations. */
    private fun jpegFile(width: Int, height: Int, orientation: Int? = null): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(DARK)
        Canvas(bitmap).drawRect(0f, 0f, width / 2f, height / 2f, Paint().apply { color = LIGHT })
        val file = File(context.cacheDir, "source-$width-$height-$orientation.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (orientation != null) {
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return file
    }

    private fun isLight(bitmap: Bitmap, x: Int, y: Int): Boolean = (bitmap.getPixel(x, y) and 0xFF) > 0x80

    private fun storedFile(name: String, ageMs: Long, dir: File = folder): File = File(dir.apply { mkdirs() }, name).also {
        it.writeText("x")
        it.setLastModified(System.currentTimeMillis() - ageMs)
    }

    @Test
    fun importDownscalesIntoThePhotoFolder() = runTest {
        val path = store.importPhoto(Uri.fromFile(jpegFile(4100, 2050)).toString())
        assertTrue(path, path.matches(Regex("photos/visit-[0-9a-f-]{36}\\.jpg")))
        assertTrue(File(context.filesDir, path).isFile)
        val loaded = store.loadPhoto(path)!!
        assertEquals(VisitPhotoStore.MAX_DIMENSION, loaded.width)
        assertTrue(abs(VisitPhotoStore.MAX_DIMENSION / 2 - loaded.height) <= 1)
        assertFalse(folder.listFiles().orEmpty().any { it.name.endsWith(".tmp") })

        val small = store.importPhoto(Uri.fromFile(jpegFile(40, 30)).toString())
        assertEquals(40, store.loadPhoto(small)!!.width)

        // Thumbnails are subsampled.
        assertTrue(store.loadPhoto(path, maxDimension = 256)!!.width < 1024)

        store.delete(path)
        assertFalse(File(context.filesDir, path).exists())
        assertNull(store.loadPhoto(path))
    }

    @Test
    fun exifOrientationIsApplied() = runTest {
        // Rotate 90° clockwise: the light top-left quadrant ends up top right, and width and height swap.
        val rotated = store.loadPhoto(store.importPhoto(Uri.fromFile(jpegFile(300, 100, ExifInterface.ORIENTATION_ROTATE_90)).toString()))!!
        assertEquals(100, rotated.width)
        assertEquals(300, rotated.height)
        assertTrue(isLight(rotated, 90, 10))
        assertFalse(isLight(rotated, 10, 10))
        assertFalse(isLight(rotated, 90, 290))

        val upsideDown = store.loadPhoto(store.importPhoto(Uri.fromFile(jpegFile(300, 100, ExifInterface.ORIENTATION_ROTATE_180)).toString()))!!
        assertEquals(300, upsideDown.width)
        assertTrue(isLight(upsideDown, 290, 90))
        assertFalse(isLight(upsideDown, 10, 10))

        val mirrored = store.loadPhoto(store.importPhoto(Uri.fromFile(jpegFile(300, 100, ExifInterface.ORIENTATION_FLIP_HORIZONTAL)).toString()))!!
        assertTrue(isLight(mirrored, 290, 10))
        assertFalse(isLight(mirrored, 10, 10))

        val rotated270 = store.loadPhoto(store.importPhoto(Uri.fromFile(jpegFile(300, 100, ExifInterface.ORIENTATION_ROTATE_270)).toString()))!!
        assertEquals(100, rotated270.width)
        assertTrue(isLight(rotated270, 10, 290))
        assertFalse(isLight(rotated270, 10, 10))
    }

    @Test
    fun unreadableExifDataCountsAsUpright() {
        val failing = object : java.io.InputStream() {
            override fun read(): Int = throw IllegalStateException("damaged EXIF block")
        }
        assertEquals(ExifInterface.ORIENTATION_NORMAL, FileVisitPhotoStore.readOrientation { failing })
        assertEquals(ExifInterface.ORIENTATION_NORMAL, FileVisitPhotoStore.readOrientation { throw SecurityException("no grant") })
        val rotated = jpegFile(30, 20, ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, FileVisitPhotoStore.readOrientation { rotated.inputStream() })
    }

    @Test
    fun rotatedLargePhotosAreDownscaledToo() = runTest {
        val path = store.importPhoto(Uri.fromFile(jpegFile(3000, 1000, ExifInterface.ORIENTATION_ROTATE_90)).toString())
        val loaded = store.loadPhoto(path)!!
        assertEquals(VisitPhotoStore.MAX_DIMENSION, loaded.height)
        assertTrue(abs(683 - loaded.width) <= 1)
    }

    private suspend fun assertImportFails(source: String) {
        try {
            store.importPhoto(source)
            fail("Importing $source should fail")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun unreadableSourcesFailWithIOException() = runTest {
        assertImportFails(Uri.fromFile(File(context.cacheDir, "notes.txt").apply { writeText("hello") }).toString())
        assertImportFails(Uri.fromFile(File(context.cacheDir, "missing.jpg")).toString())
        assertTrue(folder.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun pathsOutsideTheFolderAreIgnored() = runTest {
        val outside = File(context.filesDir, "important.txt").apply { writeText("keep") }
        val reference = storedFile("hall-1-a.jpg", ageMs = 0, dir = File(context.filesDir, "reference"))
        store.delete("important.txt")
        store.delete("photos/../important.txt")
        store.delete("reference/hall-1-a.jpg")
        store.delete("/" + outside.path)
        assertTrue(outside.exists())
        assertTrue(reference.exists())
        assertNull(store.loadPhoto("../important.txt"))
        assertNull(store.loadPhoto("reference/hall-1-a.jpg"))
    }

    @Test
    fun captureFilesAreSharedThroughTheProviderAndDeleted() = runTest {
        val uri = Uri.parse(store.createCaptureUri())
        assertEquals("content", uri.scheme)
        assertEquals(context.packageName + ".photos", uri.authority)
        val file = File(captures, uri.lastPathSegment!!)
        assertTrue(file.isFile)

        // The camera app writes the picture; it is imported like a gallery photo.
        jpegFile(64, 48).copyTo(file, overwrite = true)
        val path = store.importPhoto(uri.toString())
        assertEquals(64, store.loadPhoto(path)!!.width)

        store.deleteCapture(uri.toString())
        assertFalse(file.exists())

        // Other URIs are ignored.
        val other = storedFile("keep.jpg", ageMs = 0, dir = captures)
        store.deleteCapture(Uri.fromFile(other).toString())
        store.deleteCapture("content://${context.packageName}.photos/camera/..%2F..%2Ffiles")
        store.deleteCapture("content://elsewhere/camera/keep.jpg")
        assertTrue(other.exists())
    }

    @Test
    fun sweepDeletesOnlyOldUnreferencedFilesAndOldCaptures() = runTest {
        val referenced = storedFile("visit-a.jpg", ageMs = DAY)
        val orphan = storedFile("visit-b.jpg", ageMs = DAY)
        val leftoverTemp = storedFile("visit-c.jpg.tmp", ageMs = DAY)
        val fresh = storedFile("visit-d.jpg", ageMs = 1_000)
        val oldCapture = storedFile("capture-old.jpg", ageMs = DAY, dir = captures)
        val freshCapture = storedFile("capture-new.jpg", ageMs = 1_000, dir = captures)

        val deleted = store.deleteOrphans(setOf("photos/visit-a.jpg"), modifiedBefore = System.currentTimeMillis() - HOUR)

        assertEquals(3, deleted)
        assertTrue(referenced.exists())
        assertFalse(orphan.exists())
        assertFalse(leftoverTemp.exists())
        assertTrue(fresh.exists())
        assertFalse(oldCapture.exists())
        assertTrue(freshCapture.exists())
    }

    @Test
    fun dataContainerSweepUsesTheVisitsPhotos() = runTest {
        val data = DataContainer(context, backgroundScope, Dispatchers.Default, Dispatchers.IO)
        val keep = storedFile("visit-keep.jpg", ageMs = 8 * DAY)
        val orphan = storedFile("visit-orphan.jpg", ageMs = 8 * DAY)
        val hallId = data.hallRepository.createHall(data.cinemaRepository.createCinema("Kino"), "Saal 1")
        data.visitRepository.saveVisit(Visit(hallId = hallId, date = LocalDate.of(2026, 10, 1), photoPath = "photos/visit-keep.jpg"))

        assertEquals(1, data.sweepOrphanFiles())
        assertTrue(keep.exists())
        assertFalse(orphan.exists())
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
        const val DARK = 0xFF202020.toInt()
        const val LIGHT = 0xFFF0F0F0.toInt()
    }
}
