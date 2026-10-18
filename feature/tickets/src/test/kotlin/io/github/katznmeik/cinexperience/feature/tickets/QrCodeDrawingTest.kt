package io.github.katznmeik.cinexperience.feature.tickets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.testing.PHONE_QUALIFIERS
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.core.testing.ScreenshotSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The QR code is drawn sharply (spec §6.11): whole-pixel modules, black on white, with the quiet zone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE_QUALIFIERS)
class QrCodeDrawingTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun modulesAreWholePixelsWithAFourModuleQuietZone() {
        // 380 dp at xxhdpi is 998 px: 29 modules (21 + 2 × 4) of 34 px fit; the 12 px left over widen the quiet zone.
        assertEquals(QrGeometry(modulePx = 34, offsetPx = 142), QrGeometry.of(998, 21))
        for (side in 200..1200) {
            for (size in listOf(21, 25, 29, 33)) {
                val (m, offset) = QrGeometry.of(side, size)
                assertTrue("$side px, $size modules: as large as fits", m * (size + 8) <= side && (m + 1) * (size + 8) > side)
                assertTrue("$side px, $size modules: quiet zone", offset >= 4 * m && side - offset - size * m >= 4 * m)
            }
        }
    }

    @Test
    fun theDrawnCodeHasOnlyBlackAndWhitePixels() {
        val modules = SampleTickets.qrModules()
        composeRule.setContent {
            ScreenshotSurface { Box(Modifier.width(380.dp)) { QrCodePanel(modules) } }
        }
        val bitmap = composeRule.onNodeWithContentDescription(QR_CODE_DESCRIPTION).captureToImage().asAndroidBitmap()
        assertEquals(bitmap.width, bitmap.height)
        val (m, offset) = QrGeometry.of(bitmap.width, modules.size)
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        for (row in 0 until modules.size) {
            for (column in 0 until modules.size) {
                val expected = if (modules.isDark(row, column)) black else white
                for (y in offset + row * m until offset + (row + 1) * m) {
                    for (x in offset + column * m until offset + (column + 1) * m) {
                        assertEquals("pixel ($x, $y) of module ($row, $column)", expected, bitmap.getPixel(x, y))
                    }
                }
            }
        }
        // The quiet zone beside and above the code is white (the panel's rounded corners aside).
        val code = offset until offset + modules.size * m
        for (i in code) {
            for (q in 1..4 * m) {
                assertEquals(white, bitmap.getPixel(offset - q, i))
                assertEquals(white, bitmap.getPixel(i, offset - q))
                assertEquals(white, bitmap.getPixel(offset + modules.size * m - 1 + q, i))
                assertEquals(white, bitmap.getPixel(i, offset + modules.size * m - 1 + q))
            }
        }
    }
}
