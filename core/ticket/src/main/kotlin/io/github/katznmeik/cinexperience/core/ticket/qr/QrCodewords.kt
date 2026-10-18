package io.github.katznmeik.cinexperience.core.ticket.qr

import kotlin.math.abs

/**
 * Checks that a module grid is exactly a QR code as an encoder wrote it (ISO/IEC 18004,
 * versions 1 to 4): the codewords are read from the data modules (unmasked with the
 * format information's mask pattern, in the zig-zag order around the function
 * patterns, 7.7.3 and 8.8), split into their error correction blocks (Table 9), and
 * every block's Reed–Solomon syndromes over GF(256) must be zero (7.5.2). A single
 * wrongly sampled codeword module fails this. The up to 7 remainder bits after the
 * codewords (versions 2 to 4) are not checked: scanners ignore them, and an encoder
 * that doesn't write them as 0 still makes a valid code.
 *
 * The content is not interpreted: no mode, character count or data is read, and
 * nothing comes out but whether every block is a codeword. Nothing is corrected.
 */
internal object QrCodewords {
    /** The error correction blocks of one version and level, all of one size for versions 1 to 4. */
    internal class Blocks(val count: Int, val codewords: Int, val dataCodewords: Int) {
        val ecCodewords: Int get() = codewords - dataCodewords
        val total: Int get() = count * codewords
    }

    /** Levels in the order of [BLOCKS]: the 2-bit level indicator L = 01, M = 00, Q = 11, H = 10 as an index. */
    private val LEVEL_INDEX = intArrayOf(1, 0, 3, 2)

    /** ISO/IEC 18004:2015, Table 9, versions 1 to 4, levels L, M, Q, H. */
    private val BLOCKS = arrayOf(
        arrayOf(Blocks(1, 26, 19), Blocks(1, 26, 16), Blocks(1, 26, 13), Blocks(1, 26, 9)),
        arrayOf(Blocks(1, 44, 34), Blocks(1, 44, 28), Blocks(1, 44, 22), Blocks(1, 44, 16)),
        arrayOf(Blocks(1, 70, 55), Blocks(1, 70, 44), Blocks(2, 35, 17), Blocks(2, 35, 13)),
        arrayOf(Blocks(1, 100, 80), Blocks(2, 50, 32), Blocks(2, 50, 24), Blocks(4, 25, 9)),
    )

    /** The blocks of a [size] × [size] code with 5-bit format [formatData] (level and mask). */
    internal fun blocks(size: Int, formatData: Int): Blocks = BLOCKS[(size - 17) / 4 - 1][LEVEL_INDEX[formatData shr 3]]

    /** True if [grid] (upright, [size] 21 to 33) holds valid codewords for its format data. */
    fun isValid(grid: BooleanArray, size: Int, formatData: Int): Boolean {
        val blocks = blocks(size, formatData)
        val codewords = read(grid, size, formatData and 0b111, blocks.total) ?: return false
        // The data codewords come interleaved block by block, then the error correction
        // codewords (7.6); with blocks of one size, a block's i-th codeword is the i-th of every count.
        val block = IntArray(blocks.codewords)
        for (b in 0 until blocks.count) {
            for (i in 0 until blocks.codewords) block[i] = codewords[i * blocks.count + b]
            if (!hasZeroSyndromes(block, blocks.ecCodewords)) return false
        }
        return true
    }

    /** The [count] codewords in placement order, unmasked with [mask]; the remainder bits after them are skipped. */
    internal fun read(grid: BooleanArray, size: Int, mask: Int, count: Int): IntArray? {
        val codewords = IntArray(count)
        var index = 0
        var bits = 0
        var value = 0
        forEachDataModule(size) { row, column ->
            if (index < count) {
                val bit = grid[row * size + column] != isMasked(mask, row, column)
                value = value shl 1 or (if (bit) 1 else 0)
                if (++bits == 8) {
                    codewords[index++] = value
                    bits = 0
                    value = 0
                }
            }
        }
        return codewords.takeIf { index == count }
    }

    /**
     * Calls [action] for each data module in placement order (7.7.3): two-module wide
     * columns from the right, upwards and downwards in turn, right module first,
     * skipping the vertical timing pattern and every function pattern.
     */
    internal inline fun forEachDataModule(size: Int, action: (row: Int, column: Int) -> Unit) {
        var upwards = true
        var right = size - 1
        while (right > 0) {
            if (right == 6) right--
            for (step in 0 until size) {
                val row = if (upwards) size - 1 - step else step
                for (column in right downTo right - 1) {
                    if (!isFunction(size, row, column)) action(row, column)
                }
            }
            upwards = !upwards
            right -= 2
        }
    }

    /**
     * Finder patterns with their separators and the format information, the timing
     * patterns, the dark module and (from version 2) the alignment pattern.
     */
    internal fun isFunction(size: Int, row: Int, column: Int): Boolean =
        row <= 8 && (column <= 8 || column >= size - 8) ||
            row >= size - 8 && column <= 8 ||
            row == 6 || column == 6 ||
            size > 21 && abs(row - (size - 7)) <= 2 && abs(column - (size - 7)) <= 2

    /** The data mask patterns of 7.8.2 (Table 10): true where a module is inverted. */
    internal fun isMasked(mask: Int, row: Int, column: Int): Boolean = when (mask) {
        0 -> (row + column) % 2 == 0
        1 -> row % 2 == 0
        2 -> column % 3 == 0
        3 -> (row + column) % 3 == 0
        4 -> (row / 2 + column / 3) % 2 == 0
        5 -> row * column % 2 + row * column % 3 == 0
        6 -> (row * column % 2 + row * column % 3) % 2 == 0
        else -> ((row + column) % 2 + row * column % 3) % 2 == 0
    }

    /** The block's polynomial (first codeword the highest power) is 0 at α^0 … α^(ec − 1). */
    private fun hasZeroSyndromes(block: IntArray, ecCodewords: Int): Boolean {
        for (i in 0 until ecCodewords) {
            val point = Gf256.exp(i)
            var syndrome = 0
            for (codeword in block) syndrome = Gf256.multiply(syndrome, point) xor codeword
            if (syndrome != 0) return false
        }
        return true
    }
}

/** GF(256) with the QR code's primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 (ISO/IEC 18004, 7.5.2). */
internal object Gf256 {
    private const val PRIMITIVE = 0x11D
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var value = 1
        for (i in 0 until 255) {
            EXP[i] = value
            LOG[value] = i
            value = value shl 1
            if (value >= 256) value = value xor PRIMITIVE
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    /** α^[power], for a power of 0 or more. */
    fun exp(power: Int): Int = EXP[power % 255]

    fun multiply(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
}
