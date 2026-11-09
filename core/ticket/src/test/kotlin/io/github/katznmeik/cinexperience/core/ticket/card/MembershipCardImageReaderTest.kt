package io.github.katznmeik.cinexperience.core.ticket.card

import io.github.katznmeik.cinexperience.core.ticket.testing.ImageIoCardImageDecoder
import io.github.katznmeik.cinexperience.core.ticket.testing.TestQrCodes
import io.github.katznmeik.cinexperience.core.ticket.testing.TestScreenshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The membership card's screenshot (spec §11.4): limits, refusals, and the grid only. */
class MembershipCardImageReaderTest {
    private val reader = MembershipCardImageReader(ImageIoCardImageDecoder)

    @Test
    fun readsTheCodeOfAPngScreenshot() {
        val grid = TestQrCodes.grid(21, seed = 5)

        val result = reader.read(TestScreenshot.png(TestScreenshot.cardPage(grid).image()))

        assertTrue(result is CardImageResult.Found)
        assertTrue(TestQrCodes.sameCode(grid, (result as CardImageResult.Found).qrCode.toBooleanArray(), 21))
        assertFalse("The result shows no modules", result.toString().contains("true"))
    }

    @Test
    fun readsALargeScreenshotAtAReducedResolution() {
        // Twice a phone screenshot's resolution in each direction: decoded at half.
        val page = TestScreenshot(2054, 4000, background = 80)
        page.rect(80, 572, 1974, 3770, 255)
        val grid = TestQrCodes.grid(25, seed = 6)
        page.code(grid, 25, 400, 2000, 50.0)

        val result = reader.read(TestScreenshot.png(page.image()))

        assertTrue(TestQrCodes.sameCode(grid, (result as CardImageResult.Found).qrCode.toBooleanArray(), 25))
    }

    @Test
    fun refusesWhatIsntAReadableCard() {
        assertEquals(CardImageResult.Refused(CardImageError.NOT_AN_IMAGE), reader.read(ByteArray(0)))
        assertEquals(CardImageResult.Refused(CardImageError.NOT_AN_IMAGE), reader.read("%PDF-1.4 not an image".toByteArray()))
        assertEquals(CardImageResult.Refused(CardImageError.NO_QR_CODE), reader.read(TestScreenshot.png(TestScreenshot().image())))
        val two = TestScreenshot(1600, 1200, background = 255).apply {
            code(TestQrCodes.grid(21, seed = 1), 21, 100, 100, 20.0)
            code(TestQrCodes.grid(21, seed = 2), 21, 900, 300, 20.0)
        }
        assertEquals(CardImageResult.Refused(CardImageError.SEVERAL_QR_CODES), reader.read(TestScreenshot.png(two.image())))
    }

    @Test
    fun refusesTooLargeFilesBeforeDecoding() {
        var decoded = false
        val counting = MembershipCardImageReader { decoded = true; CardImageDecoding.NotAnImage }

        assertEquals(CardImageResult.Refused(CardImageError.TOO_LARGE), counting.read(ByteArray(MembershipCardImageLimits.MAX_FILE_BYTES + 1)))
        assertFalse(decoded)
        assertEquals(CardImageResult.Refused(CardImageError.TOO_LARGE), MembershipCardImageReader { CardImageDecoding.TooLarge }.read(ByteArray(1)))
    }

    @Test
    fun neverThrows() {
        assertEquals(CardImageResult.Refused(CardImageError.NOT_AN_IMAGE), MembershipCardImageReader { throw IllegalStateException() }.read(ByteArray(1)))
        assertEquals(CardImageResult.Refused(CardImageError.TOO_LARGE), MembershipCardImageReader { throw OutOfMemoryError() }.read(ByteArray(1)))
    }

    @Test
    fun sampleSizeKeepsTheDecodedImageWithinTheLimits() {
        assertEquals(1, MembershipCardImageLimits.sampleSize(1080, 2340))
        assertEquals(1, MembershipCardImageLimits.sampleSize(4096, 2929)) // 11,997,184 pixels
        assertEquals(2, MembershipCardImageLimits.sampleSize(4097, 100))
        assertEquals(2, MembershipCardImageLimits.sampleSize(3500, 3500)) // 12.25 million
        assertEquals(4, MembershipCardImageLimits.sampleSize(10_000, 10_000))
        assertNull(MembershipCardImageLimits.sampleSize(10_001, 10_000))
        assertNull(MembershipCardImageLimits.sampleSize(0, 100))
        assertNull(MembershipCardImageLimits.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE))
        for ((w, h) in listOf(1 to 100_000_000, 100_000_000 to 1, 9_999 to 9_999, 7_000 to 1_000)) {
            val sample = MembershipCardImageLimits.sampleSize(w, h)!!
            assertTrue(MembershipCardImageLimits.fitsDecoded((w + sample - 1) / sample, (h + sample - 1) / sample))
        }
    }
}
