package io.github.katznmeik.cinexperience.core.ticket.card

import io.github.katznmeik.cinexperience.core.ticket.QrModuleGrid
import io.github.katznmeik.cinexperience.core.ticket.qr.LuminanceImage
import io.github.katznmeik.cinexperience.core.ticket.testing.ImageIoCardImageDecoder
import io.github.katznmeik.cinexperience.core.ticket.testing.TestQrCodes
import io.github.katznmeik.cinexperience.core.ticket.testing.TestScreenshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding the one QR code in an invented screenshot of the membership card (spec §11.4). */
class QrLocatorTest {

    private fun found(image: LuminanceImage): QrModuleGrid {
        val located = QrLocator.find(image)
        assertTrue("Expected a code, got $located", located is QrLocator.Located.Found)
        return (located as QrLocator.Located.Found).grid
    }

    private fun assertSameCode(expected: BooleanArray, grid: QrModuleGrid, size: Int) {
        assertEquals(size, grid.size)
        assertTrue("Another grid than the one drawn", TestQrCodes.sameCode(expected, grid.toBooleanArray(), size))
    }

    @Test
    fun findsTheCodeOnTheCardPage() {
        val grid = TestQrCodes.grid(21)

        assertSameCode(grid, found(TestScreenshot.cardPage(grid).image()), 21)
    }

    @Test
    fun findsThreePatternsOnTheCardPage() {
        val patterns = QrLocator.patternsOf(TestScreenshot.cardPage(TestQrCodes.grid(21)).image())

        // The finder centres: 3.5 modules of 33 px in from the code's corners (167, 1122) and (860, 1815).
        assertEquals(listOf(282.5 to 1237.5, 744.5 to 1237.5, 282.5 to 1699.5).sortedBy { it.first + it.second * 10_000 },
            patterns.map { (x, y, _) -> x.toInt() + 0.5 to y.toInt() + 0.5 }.sortedBy { it.first + it.second * 10_000 })
        patterns.forEach { (_, _, module) -> assertEquals(33.0, module, 1.0) }
    }

    @Test
    fun findsCodesOfVersionsOneToFourAndSmallModules() {
        for ((size, module) in listOf(21 to 33.0, 25 to 27.0, 29 to 23.5, 33 to 20.0, 21 to 3.0, 25 to 2.6, 33 to 4.0)) {
            val grid = TestQrCodes.grid(size, seed = size)
            assertSameCode(grid, found(TestScreenshot.cardPage(grid, size, module).image()), size)
        }
    }

    @Test
    fun findsACodeTurnedByAQuarterUpright() {
        val grid = TestQrCodes.grid(21, seed = 9)
        for (degrees in listOf(90.0, 180.0, 270.0)) {
            assertSameCode(grid, found(TestScreenshot.cardPage(grid, degrees = degrees).image()), 21)
        }
    }

    @Test
    fun findsTheCodeInACompressedJpeg() {
        val grid = TestQrCodes.grid(21, seed = 3)
        val jpeg = TestQrCodes.jpeg(TestScreenshot.cardPage(grid).image(), quality = 0.4f)
        val decoded = ImageIoCardImageDecoder.decode(jpeg) as CardImageDecoding.Decoded

        assertSameCode(grid, found(decoded.image), 21)
    }

    @Test
    fun refusesASlantedCode() {
        // A photo of a screen: the grid reader would sample wrong modules.
        for (degrees in listOf(4.0, 12.0, 45.0)) {
            assertEquals(QrLocator.Located.None, QrLocator.find(TestScreenshot.cardPage(TestQrCodes.grid(21), degrees = degrees).image()))
        }
    }

    @Test
    fun refusesAPageWithoutACode() {
        val page = TestScreenshot.cardPage(BooleanArray(21 * 21))

        assertEquals(QrLocator.Located.None, QrLocator.find(page.image()))
        assertEquals(QrLocator.Located.None, QrLocator.find(LuminanceImage(400, 400, ByteArray(400 * 400) { -1 })))
        assertEquals(QrLocator.Located.None, QrLocator.find(LuminanceImage(1, 1, byteArrayOf(0))))
    }

    @Test
    fun refusesADamagedCode() {
        val grid = TestQrCodes.grid(21)
        // Data modules flipped beyond what the reader accepts: its error correction check fails.
        for (index in listOf(21 * 12 + 12, 21 * 15 + 18, 21 * 18 + 10)) grid[index] = !grid[index]

        assertEquals(QrLocator.Located.None, QrLocator.find(TestScreenshot.cardPage(grid).image()))
    }

    @Test
    fun refusesACodeHalfHidden() {
        val page = TestScreenshot.cardPage(TestQrCodes.grid(21))
        page.rect(500, 1500, 1027, 1900, 255) // the code's lower right part painted over

        assertEquals(QrLocator.Located.None, QrLocator.find(page.image()))
    }

    @Test
    fun refusesTwoCodes() {
        val page = TestScreenshot(1600, 1200, background = 255)
        page.code(TestQrCodes.grid(21, seed = 1), 21, 100, 100, 20.0)
        page.code(TestQrCodes.grid(25, seed = 2), 25, 900, 300, 20.0)

        assertEquals(QrLocator.Located.Several, QrLocator.find(page.image()))
    }

    @Test
    fun aDamagedSecondCodeDoesntCount() {
        val first = TestQrCodes.grid(21, seed = 1)
        val second = TestQrCodes.grid(21, seed = 2)
        for (index in listOf(21 * 12 + 12, 21 * 15 + 18, 21 * 18 + 10)) second[index] = !second[index]
        val page = TestScreenshot(1600, 1200, background = 255)
        page.code(first, 21, 100, 100, 20.0)
        page.code(second, 21, 900, 300, 20.0)

        assertSameCode(first, found(page.image()), 21)
    }
}
