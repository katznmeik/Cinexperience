package io.github.katznmeik.cinexperience.core.ticket.testing

import io.github.katznmeik.cinexperience.core.ticket.qr.Gf256
import io.github.katznmeik.cinexperience.core.ticket.qr.LuminanceDecoder
import io.github.katznmeik.cinexperience.core.ticket.qr.LuminanceImage
import io.github.katznmeik.cinexperience.core.ticket.qr.QrCodewords
import io.github.katznmeik.cinexperience.core.ticket.qr.QrFormat
import io.github.katznmeik.cinexperience.core.ticket.qr.luminanceOf
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Invented QR module grids for tests, written like an encoder writes them (ISO/IEC
 * 18004, 7.5 to 7.9): finder, separator, timing and alignment patterns, the dark
 * module, both format copies, and data codewords with their Reed–Solomon error
 * correction, interleaved and placed with the format's mask, remainder bits 0. The
 * data codewords are random bytes, not a bit stream: the codes have no content.
 * Test code only; the app never generates or decodes codes. The reader's own checks
 * are compared with an independent encoder's codes in `QrCodewordsTest`.
 */
internal object TestQrCodes {
    /** Format data: error correction level M (00) and mask 5, written as Yorck's code could be. */
    const val FORMAT_DATA = 0b00_101

    /** A code of [size] modules with [formatData]; its data codewords are [dataCodewords], or random from [seed]. */
    fun grid(size: Int = 21, seed: Int = 7, formatData: Int = FORMAT_DATA, dataCodewords: IntArray? = null): BooleanArray {
        val blocks = QrCodewords.blocks(size, formatData)
        val random = Random(seed)
        val data = dataCodewords ?: IntArray(blocks.count * blocks.dataCodewords) { random.nextInt(256) }
        require(data.size == blocks.count * blocks.dataCodewords) { "Expected ${blocks.count * blocks.dataCodewords} data codewords" }
        val grid = BooleanArray(size * size)
        fun set(row: Int, column: Int, dark: Boolean) {
            grid[row * size + column] = dark
        }
        for ((top, left) in listOf(0 to 0, 0 to size - 7, size - 7 to 0)) {
            for (r in 0 until 7) for (c in 0 until 7) set(top + r, left + c, max(abs(r - 3), abs(c - 3)) != 2)
        }
        for (i in 0..7) {
            set(7, i, false)
            set(i, 7, false)
            set(7, size - 1 - i, false)
            set(i, size - 8, false)
            set(size - 8, i, false)
            set(size - 1 - i, 7, false)
        }
        for (i in 8..size - 9) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }
        if (size > 21) {
            val centre = size - 7
            for (r in -2..2) for (c in -2..2) set(centre + r, centre + c, max(abs(r), abs(c)) != 1)
        }
        set(size - 8, 8, true)
        writeFormat(grid, size, QrFormat.encode(formatData))
        val codewords = interleaved(data, blocks)
        val mask = formatData and 0b111
        var bit = 0
        QrCodewords.forEachDataModule(size) { row, column ->
            val value = bit < codewords.size * 8 && (codewords[bit / 8] shr (7 - bit % 8)) and 1 == 1
            set(row, column, value != QrCodewords.isMasked(mask, row, column))
            bit++
        }
        return grid
    }

    /** The data codewords split into their blocks, each with its error correction, interleaved (7.6). */
    private fun interleaved(data: IntArray, blocks: QrCodewords.Blocks): IntArray {
        val split = (0 until blocks.count).map { b -> data.copyOfRange(b * blocks.dataCodewords, (b + 1) * blocks.dataCodewords) }
        val full = split.map { it + errorCorrection(it, blocks.ecCodewords) }
        return IntArray(blocks.total) { i -> full[i % blocks.count][i / blocks.count] }
    }

    /** The remainder of data(x) · x^[count] divided by the generator (x − α^0) … (x − α^(count − 1)). */
    fun errorCorrection(data: IntArray, count: Int): IntArray {
        var generator = intArrayOf(1)
        for (i in 0 until count) {
            val next = IntArray(generator.size + 1)
            for (j in generator.indices) {
                next[j] = next[j] xor generator[j]
                next[j + 1] = next[j + 1] xor Gf256.multiply(generator[j], Gf256.exp(i))
            }
            generator = next
        }
        val remainder = IntArray(count)
        for (codeword in data) {
            val factor = codeword xor remainder[0]
            for (j in 0 until count - 1) remainder[j] = remainder[j + 1] xor Gf256.multiply(generator[j + 1], factor)
            remainder[count - 1] = Gf256.multiply(generator[count], factor)
        }
        return remainder
    }

    /** A grid from a hex string of its modules, row by row (`1` dark), as printed by an encoder's tool. */
    fun fromHex(hex: String, size: Int): BooleanArray {
        val bits = hex.flatMap { digit -> (3 downTo 0).map { (digit.digitToInt(16) shr it) and 1 == 1 } }
        return bits.take(size * size).toBooleanArray()
    }

    /** Both copies of [word], most significant bit first, at the positions of ISO/IEC 18004, 7.9.1. */
    fun writeFormat(grid: BooleanArray, size: Int, word: Int, copies: Set<Int> = setOf(1, 2)) {
        val first = listOf(8 to 0, 8 to 1, 8 to 2, 8 to 3, 8 to 4, 8 to 5, 8 to 7, 8 to 8, 7 to 8, 5 to 8, 4 to 8, 3 to 8, 2 to 8, 1 to 8, 0 to 8)
        val second = (0 until 7).map { size - 1 - it to 8 } + (0 until 8).map { 8 to size - 8 + it }
        for ((copy, positions) in listOf(1 to first, 2 to second)) {
            if (copy !in copies) continue
            positions.forEachIndexed { i, (row, column) -> grid[row * size + column] = (word shr (14 - i)) and 1 == 1 }
        }
    }

    /** A grid read from text rows (`#` dark, `.` light). */
    fun parse(rows: List<String>): BooleanArray = rows.joinToString("").map { it == '#' }.toBooleanArray()

    /** A side of the code. */
    enum class Edge { BOTTOM, RIGHT }

    /**
     * The grid as an image: [modulePixels] px per module, [quietModules] light modules
     * around, optional box blur (radius [blur]) and uniform noise of ±[noise], dark 0..[dark], light [light].
     * A [line] of [lineDarkness] (a share of the way from light to dark) runs along the
     * code one pixel outside that edge, as a faint border of a printed code might.
     */
    fun render(
        grid: BooleanArray,
        size: Int,
        modulePixels: Double = 4.0,
        quietModules: Int = 4,
        blur: Int = 0,
        noise: Int = 0,
        dark: Int = 0,
        light: Int = 255,
        seed: Int = 3,
        line: Edge? = null,
        lineDarkness: Double = 0.2,
    ): LuminanceImage {
        val side = ((size + 2 * quietModules) * modulePixels).roundToInt()
        val codeStart = floor(quietModules * modulePixels)
        val codeEnd = floor((quietModules + size) * modulePixels)
        val lineValue = (light - lineDarkness * (light - dark)).roundToInt()
        val raw = IntArray(side * side) { index ->
            val module = { pixel: Int -> floor((pixel / modulePixels)) - quietModules }
            val y = index / side
            val x = index % side
            val row = module(y)
            val column = module(x)
            val isDark = row in 0 until size && column in 0 until size && grid[row * size + column]
            val onLine = when (line) {
                Edge.BOTTOM -> y == codeEnd + 1 && x in codeStart until codeEnd
                Edge.RIGHT -> x == codeEnd + 1 && y in codeStart until codeEnd
                null -> false
            }
            when {
                isDark -> dark
                onLine -> lineValue
                else -> light
            }
        }
        val blurred = if (blur == 0) raw else boxBlur(raw, side, blur)
        val random = Random(seed)
        val pixels = ByteArray(side * side) { i ->
            val value = blurred[i] + if (noise > 0) random.nextInt(-noise, noise + 1) else 0
            value.coerceIn(0, 255).toByte()
        }
        return LuminanceImage(side, side, pixels)
    }

    private fun floor(value: Double): Int = kotlin.math.floor(value).toInt()

    private fun boxBlur(pixels: IntArray, side: Int, radius: Int): IntArray = IntArray(pixels.size) { index ->
        val y = index / side
        val x = index % side
        var sum = 0
        var count = 0
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val yy = y + dy
            val xx = x + dx
            if (yy in 0 until side && xx in 0 until side) {
                sum += pixels[yy * side + xx]
                count++
            }
        }
        sum / count
    }

    /** [image] as an RGB JPEG (as in Yorck's tickets), encoded by the JDK at [quality]. */
    fun jpeg(image: LuminanceImage, quality: Float = 0.75f, rgb: Boolean = true): ByteArray {
        val buffered = BufferedImage(image.width, image.height, if (rgb) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_BYTE_GRAY)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val v = image.luminance(x, y)
            buffered.setRGB(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val parameters = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.write(null, IIOImage(buffered, null, null), parameters)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    /** The grid turned 90° clockwise [times] times. */
    fun rotate(grid: BooleanArray, size: Int, times: Int): BooleanArray {
        var result = grid
        repeat(times) {
            val source = result
            result = BooleanArray(size * size) { index -> source[(size - 1 - index % size) * size + index / size] }
        }
        return result
    }
}

/** The JDK's JPEG decoder in place of Android's BitmapFactory, for plain JVM tests; counts the [sizes] it is asked for. */
internal class ImageIoLuminanceDecoder : LuminanceDecoder {
    val sizes = ArrayList<Pair<Int, Int>>()

    override fun decode(jpeg: ByteArray, width: Int, height: Int): LuminanceImage? {
        sizes += width to height
        return try {
            val image = ImageIO.read(ByteArrayInputStream(jpeg)) ?: return null
            if (image.width != width || image.height != height) return null
            val pixels = ByteArray(width * height)
            for (y in 0 until height) for (x in 0 until width) pixels[y * width + x] = luminanceOf(image.getRGB(x, y)).toByte()
            LuminanceImage(width, height, pixels)
        } catch (e: Exception) {
            null
        }
    }
}
