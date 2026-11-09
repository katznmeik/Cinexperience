package io.github.katznmeik.cinexperience.core.ticket.card

import android.graphics.Bitmap
import io.github.katznmeik.cinexperience.core.ticket.qr.LuminanceImage
import io.github.katznmeik.cinexperience.core.ticket.testing.TestQrCodes
import io.github.katznmeik.cinexperience.core.ticket.testing.TestScreenshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** The Android path of the membership card's screenshot: BitmapFactory, bounds first, reduction, luminance on white. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class BitmapCardImageDecoderTest {

    private fun compress(image: LuminanceImage, format: Bitmap.CompressFormat, quality: Int = 90): ByteArray {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val row = IntArray(image.width)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val v = image.luminance(x, y)
                row[x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            bitmap.setPixels(row, 0, image.width, 0, y, image.width, 1)
        }
        return ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray().also { bitmap.recycle() }
    }

    @Test
    fun readsTheCodeOfAndroidsJpegAndPngScreenshots() {
        val grid = TestQrCodes.grid(21, seed = 11)
        val page = TestScreenshot.cardPage(grid).image()
        for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG)) {
            val result = MembershipCardImageReader().read(compress(page, format))

            assertTrue("$format: $result", result is CardImageResult.Found)
            assertTrue(TestQrCodes.sameCode(grid, (result as CardImageResult.Found).qrCode.toBooleanArray(), 21))
        }
    }

    @Test
    fun decodesLargeImagesReduced() {
        val image = LuminanceImage(4400, 300, ByteArray(4400 * 300) { -1 })

        val decoded = BitmapCardImageDecoder.decode(compress(image, Bitmap.CompressFormat.PNG)) as CardImageDecoding.Decoded

        assertEquals(2200, decoded.image.width)
        assertEquals(150, decoded.image.height)
    }

    @Test
    fun refusesWhatIsntAnImage() {
        assertEquals(CardImageDecoding.NotAnImage, BitmapCardImageDecoder.decode(ByteArray(0)))
        assertEquals(CardImageDecoding.NotAnImage, BitmapCardImageDecoder.decode("not an image at all".toByteArray()))
    }

    @Test
    fun transparentPixelsLieOnWhite() {
        assertEquals(255, BitmapCardImageDecoder.onWhite(0x00000000))
        assertEquals(0, BitmapCardImageDecoder.onWhite(0xFF000000.toInt()))
        assertEquals(128.0, BitmapCardImageDecoder.onWhite(0x80000000.toInt()).toDouble(), 1.0)
        assertEquals(255, BitmapCardImageDecoder.onWhite(0xFFFFFFFF.toInt()))
    }
}
