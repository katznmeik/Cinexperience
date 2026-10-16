package io.github.katznmeik.cinexperience.core.data

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.files.FileReferenceImageStore
import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore.Companion.MAX_DIMENSION
import io.github.katznmeik.cinexperience.core.data.files.decodeWithin
import io.github.katznmeik.cinexperience.core.data.files.sampleSizeWithin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.max

/**
 * A reference image is decoded with its longer side at most [MAX_DIMENSION] px,
 * also when it didn't come through `importImage` (a backup may bring any image up
 * to its file size limit), and an image too large for memory gives `null`, never
 * a crash.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ReferenceImageBoundsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = FileReferenceImageStore(context, Dispatchers.Unconfined)
    private val folder = File(context.filesDir, ReferenceImageStore.FOLDER).apply { mkdirs() }

    /** An image of [width] × [height] px stored directly in the folder, as a restored backup places it. */
    private fun stored(name: String, width: Int, height: Int, format: Bitmap.CompressFormat): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        File(folder, name).outputStream().use { bitmap.compress(format, 90, it) }
        bitmap.recycle()
        return "${ReferenceImageStore.FOLDER}/$name"
    }

    private fun Bitmap.longer() = max(width, height)

    @Test
    fun aLargeImageIsDecodedSubsampledWithinTheMaxDimension() = runTest {
        val wide = store.loadImage(stored("wide.png", 5000, 40, Bitmap.CompressFormat.PNG))!!
        assertTrue("${wide.width}", wide.longer() in MAX_DIMENSION / 2..MAX_DIMENSION)
        val tall = store.loadImage(stored("tall.png", 40, 5000, Bitmap.CompressFormat.PNG))!!
        assertTrue("${tall.height}", tall.longer() in MAX_DIMENSION / 2..MAX_DIMENSION)
        // Just above twice the limit: decoders round a sampled size up, so a sample of 2 would give 2,049 px.
        val jpeg = store.loadImage(stored("odd.jpg", 2 * MAX_DIMENSION + 1, 10, Bitmap.CompressFormat.JPEG))!!
        assertTrue("${jpeg.width}", jpeg.longer() <= MAX_DIMENSION)
    }

    @Test
    fun anImageWithinTheMaxDimensionKeepsItsSize() = runTest {
        val image = store.loadImage(stored("exact.png", MAX_DIMENSION, 30, Bitmap.CompressFormat.PNG))!!
        assertEquals(MAX_DIMENSION, image.width)
        assertEquals(30, image.height)
    }

    @Test
    fun aFileThatIsNoImageGivesNull() = runTest {
        File(folder, "notes.jpg").writeText("hello")
        assertNull(store.loadImage("${ReferenceImageStore.FOLDER}/notes.jpg"))
    }

    @Test
    fun runningOutOfMemoryGivesNull() {
        val path = File(folder, "huge.jpg").apply { writeText("x") }.path
        val decoded = decodeWithin(path, MAX_DIMENSION) { _, options ->
            if (options.inJustDecodeBounds) {
                options.outWidth = 60_000
                options.outHeight = 60_000
                null
            } else {
                assertEquals(32, options.inSampleSize)
                throw OutOfMemoryError("test")
            }
        }
        assertNull(decoded)
    }

    @Test
    fun theSampleSizeIsThePowerOfTwoThatBringsTheImageWithinTheLimit() {
        val cases = listOf(
            1 to 1, 2048 to 1, 2049 to 2, 4096 to 2, 4097 to 4, 8192 to 4, 8193 to 8, 60_000 to 32, Int.MAX_VALUE to 1_048_576,
        )
        for ((longer, expected) in cases) {
            assertEquals("$longer", expected, sampleSizeWithin(longer, 2048))
            // Rounded up, the sampled size is within the limit; half the sample would not be.
            assertTrue("$longer", (longer.toLong() + expected - 1) / expected <= 2048)
            if (expected > 1) assertTrue("$longer", (longer.toLong() + expected / 2 - 1) / (expected / 2) > 2048)
        }
        assertEquals(1 shl 30, sampleSizeWithin(Int.MAX_VALUE, 1))
    }
}
