package io.github.katznmeik.cinexperience.core.ticket.card

import io.github.katznmeik.cinexperience.core.ticket.QrModuleGrid
import io.github.katznmeik.cinexperience.core.ticket.qr.LuminanceImage
import io.github.katznmeik.cinexperience.core.ticket.qr.QrGridReader
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the one QR code in a screenshot of the membership card (spec §11.4): the page
 * around the card (browser bar, text, a photo, buttons) is ignored. The content is
 * never decoded.
 *
 * 1. Every pixel is dark or light by one threshold for the whole image (iterative
 *    intermeans, as for the ticket's code).
 * 2. Each row is scanned for dark–light–dark–light–dark runs in the ratio 1:1:3:1:1, a
 *    finder pattern crossed through its centre; each hit is checked down its column
 *    and again across its row (same ratio, about the same size) and gives the
 *    pattern's centre and module size. Hits close together are one pattern.
 * 3. Three patterns of about the same module size whose centres are three corners of
 *    an upright square (or one turned by a quarter), as far apart as a code of
 *    versions 1–4 puts them, are a code candidate.
 * 4. The candidate's square (the centres ± 3.5 modules) is cut out with a margin, all
 *    but half a module around it painted light, and handed to [QrGridReader], which
 *    reads and checks the grid exactly like the ticket's (finder, timing and alignment
 *    patterns, format information, error correction, pattern centres on the grid).
 *
 * Exactly one checked grid is [Located.Found]; codes in two places are [Located.Several].
 */
internal object QrLocator {

    sealed interface Located {
        data class Found(val grid: QrModuleGrid) : Located

        data object None : Located

        data object Several : Located
    }

    /** How far each 1-run may be from the module size (and each 3-run from 3 modules), as a share of a module. */
    private const val RUN_TOLERANCE = 0.5

    /** The column's crossing may differ this share from the row's in total length. */
    private const val CROSS_TOTAL_TOLERANCE = 0.4

    /** Patterns this close (in modules) with module sizes within [SAME_MODULE_RATIO] are one. */
    private const val SAME_PATTERN_DISTANCE = 1.5

    /** The module sizes of one code's three patterns differ at most this factor. */
    private const val SAME_MODULE_RATIO = 1.4

    /** The two sides of the square may differ this share in length and leave the axes by this many modules. */
    private const val SIDE_TOLERANCE = 0.1
    private const val AXIS_TOLERANCE_MODULES = 1.5

    /** The distance of the centres in modules (size − 7) may be this far from a code size's. */
    private const val SIZE_TOLERANCE_MODULES = 1.5

    /** Patterns kept (the most often crossed): bounds the work on images full of pattern-like shapes. */
    private const val MAX_PATTERNS = 16

    /** Pattern clusters gathered at most; further hits elsewhere are ignored. */
    private const val MAX_CLUSTERS = 256

    /** A pattern must be crossed in at least this many rows. */
    private const val MIN_HITS = 2

    private class Cluster(var x: Double, var y: Double, var module: Double, var hits: Int) {
        fun add(x: Double, y: Double, module: Double) {
            val n = hits + 1
            this.x += (x - this.x) / n
            this.y += (y - this.y) / n
            this.module += (module - this.module) / n
            hits = n
        }
    }

    private class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        fun overlaps(other: Box): Boolean = left < other.right && other.left < right && top < other.bottom && other.top < bottom
    }

    fun find(image: LuminanceImage): Located {
        val threshold = QrGridReader.threshold(image) ?: return Located.None
        val dark = BooleanArray(image.width * image.height) { image.luminance(it % image.width, it / image.width) <= threshold }
        val patterns = findPatterns(image.width, image.height, dark)
        val found = mutableListOf<Pair<Box, QrModuleGrid>>()
        for (box in candidates(patterns)) {
            if (found.any { it.first.overlaps(box.first) }) continue
            val grid = readGrid(image, box.first, box.second) ?: continue
            found += box.first to grid
            if (found.size > 1) return Located.Several
        }
        return found.singleOrNull()?.let { Located.Found(it.second) } ?: Located.None
    }

    /** Step 2: the finder patterns' centres and module sizes. */
    private fun findPatterns(width: Int, height: Int, dark: BooleanArray): List<Cluster> {
        val clusters = mutableListOf<Cluster>()
        val starts = IntArray(width + 1)
        for (y in 0 until height) {
            // The row's runs: starts[i] is where run i begins; runs alternate, the first is dark if starts with dark.
            var runs = 0
            val firstDark = dark[y * width]
            starts[runs++] = 0
            for (x in 1 until width) {
                if (dark[y * width + x] != dark[y * width + x - 1]) starts[runs++] = x
            }
            starts[runs] = width
            var i = if (firstDark) 0 else 1
            while (i + 4 < runs) {
                val counts = IntArray(5) { starts[i + it + 1] - starts[i + it] }
                if (isFinderRatio(counts)) {
                    val total = counts.sum()
                    val centreX = starts[i + 2] + counts[2] / 2.0
                    crossCheck(width, height, dark, centreX, y, total)?.let { (x, cy, module) -> addHit(clusters, x, cy, module) }
                }
                i += 2
            }
        }
        return clusters.filter { it.hits >= MIN_HITS }.sortedByDescending { it.hits }.take(MAX_PATTERNS)
    }

    private fun addHit(clusters: MutableList<Cluster>, x: Double, y: Double, module: Double) {
        val same = clusters.firstOrNull {
            abs(it.x - x) <= SAME_PATTERN_DISTANCE * it.module &&
                abs(it.y - y) <= SAME_PATTERN_DISTANCE * it.module &&
                max(it.module, module) <= SAME_MODULE_RATIO * min(it.module, module)
        }
        when {
            same != null -> same.add(x, y, module)
            clusters.size < MAX_CLUSTERS -> clusters += Cluster(x, y, module, 1)
        }
    }

    /** 1:1:3:1:1 within [RUN_TOLERANCE], with modules of at least [QrGridReader.MIN_MODULE_PIXELS]. */
    private fun isFinderRatio(counts: IntArray): Boolean {
        val total = counts.sum()
        val module = total / 7.0
        if (module < QrGridReader.MIN_MODULE_PIXELS) return false
        val tolerance = module * RUN_TOLERANCE
        return abs(counts[0] - module) < tolerance &&
            abs(counts[1] - module) < tolerance &&
            abs(counts[2] - 3 * module) < 3 * tolerance &&
            abs(counts[3] - module) < tolerance &&
            abs(counts[4] - module) < tolerance
    }

    /**
     * The crossing down the column through ([centreX], [y]) and then across the row
     * through its centre: both must show the ratio with about [total] pixels. The
     * pattern's centre and module size (from both crossings), or `null`.
     */
    private fun crossCheck(width: Int, height: Int, dark: BooleanArray, centreX: Double, y: Int, total: Int): Triple<Double, Double, Double>? {
        val x = floor(centreX).toInt()
        val vertical = crossing(dark, along = height, start = y, total = total) { at -> at * width + x } ?: return null
        val centreY = vertical.first
        val row = floor(centreY).toInt()
        val horizontal = crossing(dark, along = width, start = x, total = total) { at -> row * width + at } ?: return null
        return Triple(horizontal.first, centreY, (vertical.second + horizontal.second) / 14.0)
    }

    /**
     * The five runs through [start] along a line of [along] pixels ([index] maps a
     * position to the pixel): the centre and the total length of the runs if they show
     * the ratio and their total is within [CROSS_TOTAL_TOLERANCE] of [total].
     */
    private inline fun crossing(dark: BooleanArray, along: Int, start: Int, total: Int, index: (Int) -> Int): Pair<Double, Int>? {
        if (start !in 0 until along || !dark[index(start)]) return null
        val maxRun = total
        val counts = IntArray(5)
        // Backwards from start: the core (counts[2], partly), then light, then dark.
        var at = start
        while (at >= 0 && dark[index(at)] && counts[2] <= maxRun) { counts[2]++; at-- }
        while (at >= 0 && !dark[index(at)] && counts[1] <= maxRun) { counts[1]++; at-- }
        while (at >= 0 && dark[index(at)] && counts[0] <= maxRun) { counts[0]++; at-- }
        if (counts[1] == 0 || counts[0] == 0) return null
        // Forwards from start + 1.
        at = start + 1
        while (at < along && dark[index(at)] && counts[2] <= maxRun) { counts[2]++; at++ }
        val coreEnd = at
        while (at < along && !dark[index(at)] && counts[3] <= maxRun) { counts[3]++; at++ }
        while (at < along && dark[index(at)] && counts[4] <= maxRun) { counts[4]++; at++ }
        if (counts[3] == 0 || counts[4] == 0) return null
        val sum = counts.sum()
        if (abs(sum - total) > CROSS_TOTAL_TOLERANCE * total || !isFinderRatio(counts)) return null
        return (coreEnd - counts[2] / 2.0) to sum
    }

    /**
     * Step 3: the squares of three patterns, each with its module size, those whose
     * patterns were crossed most often first.
     */
    private fun candidates(patterns: List<Cluster>): List<Pair<Box, Double>> {
        val result = mutableListOf<Pair<Box, Double>>()
        for (a in patterns.indices) for (b in a + 1 until patterns.size) for (c in b + 1 until patterns.size) {
            val three = listOf(patterns[a], patterns[b], patterns[c])
            val modules = three.map { it.module }
            if (modules.max() > SAME_MODULE_RATIO * modules.min()) continue
            val module = modules.average()
            if (!isUprightSquare(three, module)) continue
            val half = 3.5 * module
            val box = Box(three.minOf { it.x } - half, three.minOf { it.y } - half, three.maxOf { it.x } + half, three.maxOf { it.y } + half)
            result += box to module
        }
        return result
    }

    /** One of the three is the corner: the sides to the other two are about equal, perpendicular and along the axes. */
    private fun isUprightSquare(three: List<Cluster>, module: Double): Boolean = three.indices.any { corner ->
        val (p, q) = three.filterIndexed { i, _ -> i != corner }
        val o = three[corner]
        val (ax, ay) = (p.x - o.x) to (p.y - o.y)
        val (bx, by) = (q.x - o.x) to (q.y - o.y)
        val lengthA = hypot(ax, ay)
        val lengthB = hypot(bx, by)
        val alongAxes = min(abs(ax), abs(ay)) <= AXIS_TOLERANCE_MODULES * module &&
            min(abs(bx), abs(by)) <= AXIS_TOLERANCE_MODULES * module
        val perpendicular = abs(ax * bx + ay * by) <= SIDE_TOLERANCE * lengthA * lengthB
        val sameLength = abs(lengthA - lengthB) <= SIDE_TOLERANCE * max(lengthA, lengthB)
        val modulesApart = (lengthA + lengthB) / 2 / module
        val sizeFits = QrModuleGrid.QR_SIZES.any { abs(modulesApart - (it - 7)) <= SIZE_TOLERANCE_MODULES }
        alongAxes && perpendicular && sameLength && sizeFits
    }

    /** Step 4: the square cut out with a margin of a module, all but half a module around the code light. */
    private fun readGrid(image: LuminanceImage, box: Box, module: Double): QrModuleGrid? {
        val left = max(0, floor(box.left - module).toInt())
        val top = max(0, floor(box.top - module).toInt())
        val right = min(image.width, ceil(box.right + module).toInt())
        val bottom = min(image.height, ceil(box.bottom + module).toInt())
        val width = right - left
        val height = bottom - top
        if (width < QrModuleGrid.QR_SIZES.first() || height < QrModuleGrid.QR_SIZES.first()) return null
        val keepLeft = box.left - module / 2
        val keepTop = box.top - module / 2
        val keepRight = box.right + module / 2
        val keepBottom = box.bottom + module / 2
        val pixels = ByteArray(width * height) { index ->
            val x = left + index % width
            val y = top + index / width
            val kept = x + 0.5 >= keepLeft && x + 0.5 <= keepRight && y + 0.5 >= keepTop && y + 0.5 <= keepBottom
            (if (kept) image.luminance(x, y) else LIGHT).toByte()
        }
        return QrGridReader.read(LuminanceImage(width, height, pixels))
    }

    private const val LIGHT = 255

    /** For tests: the patterns found in [image] as (x, y, module) rounded to a tenth. */
    internal fun patternsOf(image: LuminanceImage): List<Triple<Double, Double, Double>> {
        val threshold = QrGridReader.threshold(image) ?: return emptyList()
        val dark = BooleanArray(image.width * image.height) { image.luminance(it % image.width, it / image.width) <= threshold }
        fun Double.tenth() = (this * 10).roundToInt() / 10.0
        return findPatterns(image.width, image.height, dark).map { Triple(it.x.tenth(), it.y.tenth(), it.module.tenth()) }
    }
}
