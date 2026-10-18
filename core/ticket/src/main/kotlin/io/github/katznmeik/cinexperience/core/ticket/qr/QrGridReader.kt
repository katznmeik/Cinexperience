package io.github.katznmeik.cinexperience.core.ticket.qr

import io.github.katznmeik.cinexperience.core.ticket.QrModuleGrid
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Reads a QR code's module grid from an image of the code (spec §11.2): no
 * decoding of the content, no error correction. Yorck's code is a version-1 code
 * drawn upright with 4 px per module and a quiet zone, in a 116 × 116 px JPEG.
 *
 * 1. Threshold between the dark and the light pixels (iterative intermeans; too
 *    little contrast is no code).
 * 2. The code's bounding box: the outermost rows and columns with dark pixels, then
 *    those with at least 15 % of that box dark (so specks and blurred edges
 *    don't count). It must be about square.
 * 3. For 21, 25, 29 and 33 modules: the module centres are sampled (the mean of a
 *    small window around each), in each of the four 90° rotations.
 * 4. A grid is accepted only if its three finder patterns, their separators, both
 *    timing patterns, the dark module, the alignment pattern (from 25 modules on)
 *    and both copies of the format information check out, and both copies are the
 *    same valid word ([QrFormat]). Requiring both copies is strict on purpose: the
 *    redrawn code must scan, and a sampling error in the format bits is the one
 *    error the reader can see.
 * 5. The patterns' centres lie on the grid ([isOnTheGrid]), so a slanted code isn't
 *    read with wrong modules.
 *
 * Every refusal only means that no QR code is kept; the booking number stays.
 */
internal object QrGridReader {
    /** Fewer pixels per module can't be sampled reliably in a JPEG. */
    const val MIN_MODULE_PIXELS: Double = 2.0

    /** Dark and light pixels must differ at least this much in mean luminance. */
    const val MIN_CONTRAST: Double = 64.0

    /** Rows and columns with fewer dark pixels than this don't count for the bounding box (JPEG specks). */
    private const val MIN_DARK_PIXELS = 2

    /**
     * The share of the rough bounding box's width an outer row or column of the code has
     * dark at least: each holds the edge of one or two finder patterns, 7 or 14 of at
     * most 33 modules (21 %); with a margin for a rough box made wider by specks.
     */
    private const val EDGE_SHARE = 0.15

    /** The bounding box may be this share of a module less square. */
    private const val SQUARENESS = 0.5

    /** How far (in modules) a pattern's centre may lie from where the grid puts it. */
    private const val MAX_CENTRE_OFFSET = 0.3

    fun read(image: LuminanceImage): QrModuleGrid? {
        if (image.width < QrModuleGrid.QR_SIZES.first() || image.height < QrModuleGrid.QR_SIZES.first()) return null
        val threshold = threshold(image) ?: return null
        val box = boundingBox(image, threshold) ?: return null
        for (size in QrModuleGrid.QR_SIZES) {
            val module = box.side / size
            if (module < MIN_MODULE_PIXELS) continue
            if (abs(box.width - box.height) > max(1.0, SQUARENESS * module)) continue
            var grid = sample(image, threshold, box, size)
            if (!isOnTheGrid(image, threshold, box, size, grid)) continue
            repeat(4) {
                if (isValid(grid, size)) return QrModuleGrid(size, grid)
                grid = rotate(grid, size)
            }
        }
        return null
    }

    /**
     * Step 5: the centres of the finder patterns' cores (and of the alignment pattern)
     * lie where the axis-aligned grid puts them, within [MAX_CENTRE_OFFSET] of a module.
     * A code drawn slanted can pass every structural check with wrongly sampled data
     * modules; its pattern centres are off the grid.
     */
    private fun isOnTheGrid(image: LuminanceImage, threshold: Double, box: Box, size: Int, grid: BooleanArray): Boolean {
        val corners = listOf(0 to 0, 0 to size - 7, size - 7 to 0, size - 7 to size - 7)
        val finders = corners.filter { (top, left) -> isFinder(grid, size, top, left) }
        if (finders.size != 3) return false
        for ((top, left) in finders) {
            // The 3 × 3 dark core spans modules 2 to 5 of the finder; half the light ring around it is looked at too.
            if (!isCentred(image, threshold, box, size, top + 3.5, left + 3.5, halfSide = 2.0)) return false
        }
        if (size > 21) {
            val (emptyTop, emptyLeft) = corners.first { it !in finders }
            val row = if (emptyTop == 0) 6 else size - 7
            val column = if (emptyLeft == 0) 6 else size - 7
            // The alignment pattern's dark centre module and half its light ring (the full
            // ring would reach the dark ring at fractional module sizes).
            if (!isCentred(image, threshold, box, size, row + 0.5, column + 0.5, halfSide = 1.0)) return false
        }
        return true
    }

    /** The dark pixels within [halfSide] modules of ([row], [column]) (in modules) are centred there. */
    private fun isCentred(image: LuminanceImage, threshold: Double, box: Box, size: Int, row: Double, column: Double, halfSide: Double): Boolean {
        val moduleWidth = box.width / size
        val moduleHeight = box.height / size
        var count = 0
        var sumX = 0.0
        var sumY = 0.0
        val fromY = max(0, floor(box.top + (row - halfSide) * moduleHeight).toInt())
        val toY = min(image.height - 1, ceil(box.top + (row + halfSide) * moduleHeight).toInt())
        val fromX = max(0, floor(box.left + (column - halfSide) * moduleWidth).toInt())
        val toX = min(image.width - 1, ceil(box.left + (column + halfSide) * moduleWidth).toInt())
        for (y in fromY..toY) {
            val moduleY = (y + 0.5 - box.top) / moduleHeight
            if (abs(moduleY - row) > halfSide) continue
            for (x in fromX..toX) {
                val moduleX = (x + 0.5 - box.left) / moduleWidth
                if (abs(moduleX - column) > halfSide || image.luminance(x, y) > threshold) continue
                count++
                sumX += moduleX
                sumY += moduleY
            }
        }
        return count > 0 && abs(sumX / count - column) <= MAX_CENTRE_OFFSET && abs(sumY / count - row) <= MAX_CENTRE_OFFSET
    }

    private class Box(val left: Int, val top: Int, val width: Double, val height: Double) {
        val side: Double get() = (width + height) / 2
    }

    /** Iterative intermeans (Ridler–Calvard): halfway between the mean dark and the mean light luminance. */
    private fun threshold(image: LuminanceImage): Double? {
        val histogram = LongArray(256)
        for (y in 0 until image.height) for (x in 0 until image.width) histogram[image.luminance(x, y)]++
        var threshold = histogram.indices.sumOf { it * histogram[it] }.toDouble() / histogram.sum()
        var dark = 0.0
        var light = 0.0
        repeat(64) {
            var darkCount = 0L
            var darkSum = 0L
            var lightCount = 0L
            var lightSum = 0L
            for (value in histogram.indices) {
                if (value <= threshold) {
                    darkCount += histogram[value]
                    darkSum += value * histogram[value]
                } else {
                    lightCount += histogram[value]
                    lightSum += value * histogram[value]
                }
            }
            if (darkCount == 0L || lightCount == 0L) return null
            dark = darkSum.toDouble() / darkCount
            light = lightSum.toDouble() / lightCount
            val next = (dark + light) / 2
            if (abs(next - threshold) < 0.5) {
                threshold = next
                return threshold.takeIf { light - dark >= MIN_CONTRAST }
            }
            threshold = next
        }
        return threshold.takeIf { light - dark >= MIN_CONTRAST }
    }

    private fun boundingBox(image: LuminanceImage, threshold: Double): Box? {
        val rows = IntArray(image.height)
        val columns = IntArray(image.width)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.luminance(x, y) <= threshold) {
                    rows[y]++
                    columns[x]++
                }
            }
        }
        val top = rows.indexOfFirst { it >= MIN_DARK_PIXELS }
        val bottom = rows.indexOfLast { it >= MIN_DARK_PIXELS }
        val left = columns.indexOfFirst { it >= MIN_DARK_PIXELS }
        val right = columns.indexOfLast { it >= MIN_DARK_PIXELS }
        if (top < 0 || left < 0) return null
        // Second pass: rows and columns with at least EDGE_SHARE of the rough box dark, as
        // the code's outer ones are. Blurred, noisy edges just outside the code have only a
        // few dark pixels.
        val minInRow = max(MIN_DARK_PIXELS, ceil(EDGE_SHARE * (right - left + 1)).toInt())
        val minInColumn = max(MIN_DARK_PIXELS, ceil(EDGE_SHARE * (bottom - top + 1)).toInt())
        val refinedTop = rows.indexOfFirst { it >= minInRow }
        val refinedBottom = rows.indexOfLast { it >= minInRow }
        val refinedLeft = columns.indexOfFirst { it >= minInColumn }
        val refinedRight = columns.indexOfLast { it >= minInColumn }
        if (refinedTop < 0 || refinedLeft < 0) return null
        return Box(refinedLeft, refinedTop, (refinedRight - refinedLeft + 1).toDouble(), (refinedBottom - refinedTop + 1).toDouble())
    }

    private fun sample(image: LuminanceImage, threshold: Double, box: Box, size: Int): BooleanArray {
        val moduleWidth = box.width / size
        val moduleHeight = box.height / size
        val radius = max(0, floor(min(moduleWidth, moduleHeight) * 0.2).toInt())
        return BooleanArray(size * size) { index ->
            val row = index / size
            val column = index % size
            val centreX = floor(box.left + (column + 0.5) * moduleWidth).toInt()
            val centreY = floor(box.top + (row + 0.5) * moduleHeight).toInt()
            var sum = 0
            var count = 0
            for (y in max(0, centreY - radius)..min(image.height - 1, centreY + radius)) {
                for (x in max(0, centreX - radius)..min(image.width - 1, centreX + radius)) {
                    sum += image.luminance(x, y)
                    count++
                }
            }
            count > 0 && sum.toDouble() / count <= threshold
        }
    }

    /** The grid turned 90° clockwise. */
    internal fun rotate(grid: BooleanArray, size: Int): BooleanArray =
        BooleanArray(size * size) { index ->
            val row = index / size
            val column = index % size
            grid[(size - 1 - column) * size + row]
        }

    private fun isFinder(grid: BooleanArray, size: Int, top: Int, left: Int): Boolean {
        for (r in 0 until 7) for (c in 0 until 7) {
            if (grid[(top + r) * size + left + c] != (max(abs(r - 3), abs(c - 3)) != 2)) return false
        }
        return true
    }

    /** The structural checks of step 4 on an upright grid. */
    internal fun isValid(grid: BooleanArray, size: Int): Boolean {
        fun dark(row: Int, column: Int) = grid[row * size + column]
        if (!isFinder(grid, size, 0, 0) || !isFinder(grid, size, 0, size - 7) || !isFinder(grid, size, size - 7, 0)) return false
        for (i in 0..7) {
            if (dark(7, i) || dark(i, 7)) return false // top left
            if (dark(7, size - 1 - i) || dark(i, size - 8)) return false // top right
            if (dark(size - 8, i) || dark(size - 1 - i, 7)) return false // bottom left
        }
        for (i in 8..size - 9) {
            if (dark(6, i) != (i % 2 == 0) || dark(i, 6) != (i % 2 == 0)) return false
        }
        if (!dark(size - 8, 8)) return false
        if (size > 21) {
            val centre = size - 7
            for (r in -2..2) for (c in -2..2) {
                if (dark(centre + r, centre + c) != (max(abs(r), abs(c)) != 1)) return false
            }
        }
        val (first, second) = formatWords(grid, size)
        return first == second && QrFormat.isValid(first)
    }

    /** Both copies of the format information, most significant bit first (ISO/IEC 18004, 7.9.1). */
    internal fun formatWords(grid: BooleanArray, size: Int): Pair<Int, Int> {
        fun bit(row: Int, column: Int) = if (grid[row * size + column]) 1 else 0
        var first = 0
        for (column in 0..5) first = first shl 1 or bit(8, column)
        first = first shl 1 or bit(8, 7)
        first = first shl 1 or bit(8, 8)
        first = first shl 1 or bit(7, 8)
        for (row in 5 downTo 0) first = first shl 1 or bit(row, 8)
        var second = 0
        for (row in size - 1 downTo size - 7) second = second shl 1 or bit(row, 8)
        for (column in size - 8 until size) second = second shl 1 or bit(8, column)
        return first to second
    }
}
