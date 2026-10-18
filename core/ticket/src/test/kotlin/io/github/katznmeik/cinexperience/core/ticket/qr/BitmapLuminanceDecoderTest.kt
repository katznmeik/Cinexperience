package io.github.katznmeik.cinexperience.core.ticket.qr

import android.graphics.Bitmap
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfReader
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfResult
import io.github.katznmeik.cinexperience.core.ticket.testing.InventedTicketPdf
import io.github.katznmeik.cinexperience.core.ticket.testing.TestQrCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** The Android JPEG path: BitmapFactory (bounds first), luminance, the grid; and the public reader as the app builds it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class BitmapLuminanceDecoderTest {

    /** [image] as an RGB JPEG encoded by Android, as Yorck's ticket system might. */
    private fun androidJpeg(image: LuminanceImage, quality: Int = 80): ByteArray {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val v = image.luminance(x, y)
            bitmap.setPixel(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun decodesAJpegToItsLuminance() {
        val grid = TestQrCodes.grid(21)
        val jpeg = androidJpeg(TestQrCodes.render(grid, 21))

        val image = BitmapLuminanceDecoder.decode(jpeg, 116, 116)

        assertNotNull(image)
        assertTrue(image!!.luminance(0, 0) > 200)
        assertTrue(image.luminance(17, 17) < 60) // inside the top left finder
        assertTrue(QrGridReader.read(image)!!.toBooleanArray().contentEquals(grid))
    }

    @Test
    fun decodesTheJdksJpegsToo() {
        val grid = TestQrCodes.grid(25, seed = 4)
        val image = BitmapLuminanceDecoder.decode(TestQrCodes.jpeg(TestQrCodes.render(grid, 25), 0.5f), 132, 132)

        assertTrue(QrGridReader.read(image!!)!!.toBooleanArray().contentEquals(grid))
    }

    @Test
    fun refusesAnotherSizeThanThePdfStatesAndLargeImages() {
        val jpeg = androidJpeg(TestQrCodes.render(TestQrCodes.grid(21), 21))
        val large = androidJpeg(LuminanceImage(600, 20, ByteArray(600 * 20) { 128.toByte() }))

        assertNull(BitmapLuminanceDecoder.decode(jpeg, 120, 116))
        assertNull(BitmapLuminanceDecoder.decode(jpeg, 116, 120))
        assertNull(BitmapLuminanceDecoder.decode(large, 600, 20))
    }

    @Test
    fun garbageGivesNullNeverAnException() {
        for (bytes in listOf(ByteArray(0), ByteArray(100), byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "not an image".toByteArray())) {
            assertNull(BitmapLuminanceDecoder.decode(bytes, 116, 116))
        }
        // A cut-off JPEG may decode partly (the rest grey): then no grid, or the right one.
        val grid = TestQrCodes.grid(21)
        val jpeg = androidJpeg(TestQrCodes.render(grid, 21))
        for (length in listOf(jpeg.size / 3, jpeg.size / 2, jpeg.size - 10)) {
            val partial = BitmapLuminanceDecoder.decode(jpeg.copyOf(length), 116, 116) ?: continue
            val read = QrGridReader.read(partial)
            assertTrue("cut at $length", read == null || read.toBooleanArray().contentEquals(grid))
        }
    }

    @Test
    fun theAppsReaderReadsTheInventedTicketsQrCode() {
        val grid = TestQrCodes.grid(21, seed = 21)
        val pdf = InventedTicketPdf.build(qrJpeg = androidJpeg(TestQrCodes.render(grid, 21)))

        val content = (TicketPdfReader().read(pdf) as TicketPdfResult.Read).content

        assertEquals(21, content.qrCode.valueOrNull?.size)
        assertTrue(content.qrCode.valueOrNull!!.toBooleanArray().contentEquals(grid))
    }
}
