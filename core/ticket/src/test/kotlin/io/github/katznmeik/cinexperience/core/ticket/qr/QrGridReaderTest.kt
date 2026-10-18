package io.github.katznmeik.cinexperience.core.ticket.qr

import io.github.katznmeik.cinexperience.core.ticket.testing.ImageIoLuminanceDecoder
import io.github.katznmeik.cinexperience.core.ticket.testing.TestQrCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** The module grid reader (spec §11.2) on invented grids; it never decodes content. */
class QrGridReaderTest {

    private fun assertReads(grid: BooleanArray, size: Int, image: LuminanceImage, message: String = "") {
        val read = QrGridReader.read(image)
        assertNotNull("Not read: $message", read)
        assertEquals(message, size, read!!.size)
        assertTrue("Wrong modules: $message", read.toBooleanArray().contentEquals(grid))
    }

    private fun jpegRoundTrip(image: LuminanceImage, quality: Float): LuminanceImage =
        ImageIoLuminanceDecoder().decode(TestQrCodes.jpeg(image, quality), image.width, image.height)!!

    @Test
    fun readsEveryAcceptedSizeAtSeveralScales() {
        for (size in listOf(21, 25, 29, 33)) {
            val grid = TestQrCodes.grid(size, seed = size)
            for (pixels in listOf(2.0, 3.0, 3.6, 4.0, 6.0, 9.5)) {
                if ((size + 8) * pixels > 512) continue
                assertReads(grid, size, TestQrCodes.render(grid, size, modulePixels = pixels), "$size modules at $pixels px")
            }
        }
    }

    @Test
    fun readsEverySizeAtFractionalModuleSizes() {
        // With the alignment pattern's centre window reaching its dark ring, versions 2-4
        // were refused at many of these sizes.
        val failed = ArrayList<String>()
        for (size in listOf(21, 25, 29, 33)) for (tenths in 25..60) {
            val grid = TestQrCodes.grid(size, seed = size * 100 + tenths)
            val read = QrGridReader.read(TestQrCodes.render(grid, size, modulePixels = tenths / 10.0))
            if (read?.toBooleanArray()?.contentEquals(grid) != true) failed += "$size modules at ${tenths / 10.0} px"
        }
        assertEquals(emptyList<String>(), failed)
    }

    @Test
    fun readsACodeWhoseBottomRowAndRightColumnAreMostlyLight() {
        // Data codewords that leave every data module light (level L: one block, its data
        // placed first, from the right). The right column then has only the finder's edge and
        // a format bit dark, 7 or 8 of 33 modules, about 21 % of the code's width.
        val size = 33
        val format = 0b01_101
        val light = ArrayList<Boolean>()
        QrCodewords.forEachDataModule(size) { row, column -> light += QrCodewords.isMasked(format and 0b111, row, column) }
        val data = IntArray(QrCodewords.blocks(size, format).dataCodewords) { i -> (0 until 8).fold(0) { byte, bit -> byte shl 1 or (if (light[i * 8 + bit]) 1 else 0) } }
        val grid = TestQrCodes.grid(size, formatData = format, dataCodewords = data)
        val right = (0 until size).count { grid[it * size + size - 1] }
        val bottom = (0 until size).count { grid[(size - 1) * size + it] }
        assertTrue("$right and $bottom dark", right <= 8 && bottom <= 12)
        assertTrue(QrGridReader.isValid(grid, size))

        assertReads(grid, size, TestQrCodes.render(grid, size, modulePixels = 4.0), "mostly light edges")
        assertReads(grid, size, jpegRoundTrip(TestQrCodes.render(grid, size, modulePixels = 3.0, blur = 1, noise = 20), 0.75f), "blurred")
    }

    @Test
    fun readsYorcksShapeThroughAJpeg() {
        // 21 modules at 4 px with a 4-module quiet zone: 116 × 116 px, as on the real ticket.
        val grid = TestQrCodes.grid(21)
        val image = TestQrCodes.render(grid, 21)
        assertEquals(116, image.width)

        for (quality in listOf(0.9f, 0.75f, 0.5f, 0.3f)) {
            assertReads(grid, 21, jpegRoundTrip(image, quality), "quality $quality")
        }
    }

    @Test
    fun readsBlurredNoisyAndLowContrastImages() {
        val grid = TestQrCodes.grid(25, seed = 11)

        assertReads(grid, 25, TestQrCodes.render(grid, 25, modulePixels = 4.0, blur = 1, noise = 40), "blur and noise")
        assertReads(grid, 25, TestQrCodes.render(grid, 25, modulePixels = 6.0, blur = 2, noise = 25), "strong blur")
        assertReads(grid, 25, TestQrCodes.render(grid, 25, modulePixels = 4.0, dark = 70, light = 190, noise = 20), "low contrast")
        assertReads(grid, 25, jpegRoundTrip(TestQrCodes.render(grid, 25, modulePixels = 3.0, blur = 1, noise = 30), 0.5f), "blur, noise and JPEG")
    }

    @Test
    fun readsACodeWithoutQuietZoneAndIgnoresSingleSpecks() {
        val grid = TestQrCodes.grid(21, seed = 5)
        assertReads(grid, 21, TestQrCodes.render(grid, 21, quietModules = 0), "no quiet zone")

        val image = TestQrCodes.render(grid, 21)
        val pixels = ByteArray(image.width * image.height) { image.luminance(it % image.width, it / image.width).toByte() }
        pixels[2 * image.width + 3] = 0
        pixels[110 * image.width + 112] = 0
        assertReads(grid, 21, LuminanceImage(image.width, image.height, pixels), "specks")
    }

    @Test
    fun turnsQuarterTurnedCodesUpright() {
        val grid = TestQrCodes.grid(29, seed = 9)
        for (turns in 1..3) {
            assertReads(grid, 29, TestQrCodes.render(TestQrCodes.rotate(grid, 29, turns), 29), "$turns quarter turns")
        }
    }

    @Test
    fun aSlantedCodeIsRefusedOrReadCorrectlyNeverWrongly() {
        // Without the pattern-centre check, a code slanted by 2° passed every structural check
        // with wrongly sampled data modules.
        var read = 0
        var refused = 0
        for (size in listOf(21, 25)) for (seed in 1..3) for (pixels in listOf(3.0, 4.0, 6.0)) {
            val grid = TestQrCodes.grid(size, seed = seed)
            val upright = TestQrCodes.render(grid, size, modulePixels = pixels)
            for (tenths in 5..120 step 5) {
                val result = QrGridReader.read(rotated(upright, tenths / 10.0))
                if (result == null) {
                    refused++
                } else {
                    read++
                    assertTrue("${tenths / 10.0}\u00b0, $size modules at $pixels px: wrong modules", result.toBooleanArray().contentEquals(grid))
                }
            }
            assertNull("45\u00b0 can't be sampled on the axes", QrGridReader.read(rotated(upright, 45.0)))
        }
        assertTrue("Slightly slanted codes are still read: $read read, $refused refused", read > 0 && refused > 0)
    }

    @Test
    fun aCodeWithTooSmallModulesIsRefused() {
        val grid = TestQrCodes.grid(21)

        assertNull(QrGridReader.read(TestQrCodes.render(grid, 21, modulePixels = 1.0, quietModules = 4)))
        assertNull(QrGridReader.read(TestQrCodes.render(grid, 21, modulePixels = 1.5, quietModules = 2)))
        assertNull(QrGridReader.read(LuminanceImage(20, 20, ByteArray(400))))
    }

    @Test
    fun garbageIsRefused() {
        val random = Random(42)
        val side = 116
        val images = mapOf(
            "white" to ByteArray(side * side) { 255.toByte() },
            "black" to ByteArray(side * side),
            "grey" to ByteArray(side * side) { 128.toByte() },
            "noise" to ByteArray(side * side) { random.nextInt(256).toByte() },
            "checkerboard" to ByteArray(side * side) { if ((it % side / 4 + it / side / 4) % 2 == 0) 0 else 255.toByte() },
            "gradient" to ByteArray(side * side) { (it % side * 2).toByte() },
            "stripes" to ByteArray(side * side) { if (it % side / 8 % 2 == 0) 0 else 255.toByte() },
            "a black square" to ByteArray(side * side) { if (it % side in 16 until 100 && it / side in 16 until 100) 0 else 255.toByte() },
        )
        for ((name, pixels) in images) {
            assertNull(name, QrGridReader.read(LuminanceImage(side, side, pixels)))
        }
    }

    @Test
    fun eachStructuralCheckIsNeeded() {
        val size = 25
        val good = TestQrCodes.grid(size, seed = 3)
        assertTrue(QrGridReader.isValid(good, size))
        fun flipped(row: Int, column: Int) = good.copyOf().also { it[row * size + column] = !it[row * size + column] }

        val broken = mapOf(
            "top left finder" to flipped(3, 3),
            "top right finder" to flipped(0, size - 1),
            "bottom left finder" to flipped(size - 5, 2),
            "separator" to flipped(7, 3),
            "timing row" to flipped(6, 10),
            "timing column" to flipped(12, 6),
            "dark module" to flipped(size - 8, 8),
            "alignment pattern" to flipped(size - 7, size - 6),
            "format copy 1" to flipped(8, 2),
            "format copy 2" to flipped(size - 3, 8),
            "a data module" to flipped(size - 1, size - 1),
            "an error correction module" to flipped(10, 1),
        )
        for ((name, grid) in broken) {
            assertTrue(name, !QrGridReader.isValid(grid, size))
            assertNull(name, QrGridReader.read(TestQrCodes.render(grid, size)))
        }
    }

    @Test
    fun bothFormatCopiesMustBeTheSameValidWord() {
        val size = 21
        val grid = TestQrCodes.grid(size)
        // Copy 2 another valid word: each copy alone checks out, together they don't.
        TestQrCodes.writeFormat(grid, size, QrFormat.encode(0b11_010), copies = setOf(2))

        val (first, second) = QrGridReader.formatWords(grid, size)
        assertTrue(QrFormat.isValid(first) && QrFormat.isValid(second))
        assertNull(QrGridReader.read(TestQrCodes.render(grid, size)))
    }

    @Test
    fun acceptsACodeFromAnIndependentEncoder() {
        // A real encoder's code for the invented text "TEST123" (level M), generated once
        // outside the app: checks the format positions against another implementation.
        val rows = listOf(
            "#######.#.#...#######",
            "#.....#.#..#..#.....#",
            "#.###.#....##.#.###.#",
            "#.###.#.####..#.###.#",
            "#.###.#..###..#.###.#",
            "#.....#..#.##.#.....#",
            "#######.#.#.#.#######",
            "........##.##........",
            "#.##.###...##.#..#.##",
            "....#...#.#####....##",
            "#.#.#.#..###.....####",
            "#....#...#.#..###..##",
            "##..#.#####.#..#.##..",
            "........##.#..#..#.##",
            "#######.#..##..#..#.#",
            "#.....#.#......##.###",
            "#.###.#..#..######..#",
            "#.###.#.##.#..#.#.##.",
            "#.###.#.#.#.#.##.#...",
            "#.....#..#...#.##.#..",
            "#######.###..#..#..#.",
        )
        val grid = TestQrCodes.parse(rows)

        val (first, second) = QrGridReader.formatWords(grid, 21)
        assertEquals(first, second)
        assertEquals("level M", 0b00, (QrFormat.VALID_WORDS.indexOf(first) shr 3) and 0b11)
        assertReads(grid, 21, jpegRoundTrip(TestQrCodes.render(grid, 21), 0.75f), "independent encoder")
    }

    @Test(timeout = 120_000)
    fun harshRendersAreReadCorrectlyOrRefusedNeverWrongly() {
        // 2 to 5 px per module, blur, noise, JPEG at 0.6 and 0.9, and a faint line of
        // 18 to 24 % one pixel outside the bottom or right edge: without the Reed–Solomon
        // check, 3 of these were read with wrong modules (a review's larger sweep found
        // dozens). The remainder bits, which scanners ignore, may differ.
        val wrong = ArrayList<String>()
        var read = 0
        var total = 0
        for (size in listOf(21, 25, 33)) for (tenths in listOf(20, 25, 30, 35, 40, 50)) for (blur in 0..2) for (noise in listOf(0, 30)) {
            for (quality in listOf(0.6f, 0.9f)) for (line in listOf(null, TestQrCodes.Edge.BOTTOM, TestQrCodes.Edge.RIGHT)) {
                val grid = TestQrCodes.grid(size, seed = total)
                val render = TestQrCodes.render(
                    grid, size, modulePixels = tenths / 10.0, blur = blur, noise = noise, seed = total,
                    line = line, lineDarkness = 0.18 + 0.02 * (total % 4),
                )
                val result = QrGridReader.read(jpegRoundTrip(render, quality))
                total++
                if (result == null) continue
                read++
                if (!TestQrCodes.sameCode(result.toBooleanArray(), grid, size)) wrong += "$size modules at ${tenths / 10.0} px, blur $blur, noise $noise, quality $quality, line $line"
            }
        }
        assertEquals(emptyList<String>(), wrong)
        assertTrue("$read of $total read", read > total / 3)
    }

    @Test(timeout = 60_000)
    fun yorcksShapeIsAlwaysRead() {
        // 21 modules at 4 px with a 4-module quiet zone in a JPEG, with some blur and noise.
        val failed = ArrayList<String>()
        for (seed in 0 until 30) for (blur in 0..1) for (noise in listOf(0, 20)) for (quality in listOf(0.6f, 0.75f, 0.9f)) {
            val grid = TestQrCodes.grid(21, seed = seed, formatData = seed % 32)
            val result = QrGridReader.read(jpegRoundTrip(TestQrCodes.render(grid, 21, blur = blur, noise = noise, seed = seed), quality))
            if (result?.toBooleanArray()?.contentEquals(grid) != true) failed += "seed $seed, blur $blur, noise $noise, quality $quality"
        }
        assertEquals(emptyList<String>(), failed)
    }

    /** [image] turned by [degrees] around its centre (nearest neighbour, white outside). */
    private fun rotated(image: LuminanceImage, degrees: Double): LuminanceImage {
        val angle = Math.toRadians(degrees)
        val centre = image.width / 2.0
        val pixels = ByteArray(image.width * image.height) { index ->
            val x = index % image.width - centre
            val y = index / image.width - centre
            val sourceX = (x * cos(angle) + y * sin(angle) + centre).roundToInt()
            val sourceY = (-x * sin(angle) + y * cos(angle) + centre).roundToInt()
            if (sourceX in 0 until image.width && sourceY in 0 until image.height) image.luminance(sourceX, sourceY).toByte() else 255.toByte()
        }
        return LuminanceImage(image.width, image.height, pixels)
    }
}
