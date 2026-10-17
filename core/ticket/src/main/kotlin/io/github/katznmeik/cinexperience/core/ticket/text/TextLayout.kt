package io.github.katznmeik.cinexperience.core.ticket.text

import io.github.katznmeik.cinexperience.core.ticket.TicketText
import kotlin.math.abs
import kotlin.math.max

/**
 * A run of text on one baseline without a wide gap: a label with its value
 * ("Saal 4") or one cell of a column. [x] and [y] are its start on the baseline in
 * default user space (y up), [size] its largest font size; [isBold] if all its
 * glyphs are bold. [text] is cleaned ([TicketText.clean]) and never empty.
 */
internal data class TextSegment(val x: Double, val y: Double, val size: Double, val isBold: Boolean, val text: String)

/** The segments on one baseline, left to right. */
internal class TextLine(val y: Double, val segments: List<TextSegment>)

/**
 * Puts a page's glyphs into reading order: lines by baseline from the top, each
 * sorted by x, split into segments where the gap is wide.
 *
 * Calibrated on Yorck's ticket (Qt 5.5 places each glyph with its own `Td`): glyphs
 * of one word follow each other within 0.01 em (kerning pulls some closer), spaces
 * are drawn as glyphs (mapped to a tab), a label and its value are one space plus at
 * most about 1.1 em apart, and the columns and the cells of one line ("Saal",
 * "Reihe", "Platz") are more than 3 em apart.
 */
internal object TextLayout {
    /** Glyphs whose baselines differ by at most this share of the font size are on one line. */
    const val BASELINE_TOLERANCE: Double = 0.2

    /** A gap wider than this share of the font size gets a space, if there isn't one. */
    const val SPACE_GAP: Double = 0.15

    /** A gap wider than this share of the font size starts a new segment. */
    const val SEGMENT_GAP: Double = 1.5

    fun lines(glyphs: List<Glyph>): List<TextLine> {
        val sorted = glyphs.sortedWith(compareByDescending<Glyph> { it.y }.thenBy { it.x })
        val lines = ArrayList<TextLine>()
        var start = 0
        while (start < sorted.size) {
            val first = sorted[start]
            var end = start + 1
            while (end < sorted.size && abs(first.y - sorted[end].y) <= max(MIN_TOLERANCE, BASELINE_TOLERANCE * minOf(first.size, sorted[end].size))) end++
            val line = sorted.subList(start, end).sortedBy { it.x }
            val segments = segments(line)
            if (segments.isNotEmpty()) lines += TextLine(first.y, segments)
            start = end
        }
        return lines
    }

    private fun segments(line: List<Glyph>): List<TextSegment> {
        val segments = ArrayList<TextSegment>()
        var current = ArrayList<Glyph>()
        var end = Double.NEGATIVE_INFINITY
        for (glyph in line) {
            if (current.isNotEmpty() && glyph.x - end > SEGMENT_GAP * max(glyph.size, current.last().size)) {
                segment(current)?.let(segments::add)
                current = ArrayList()
            }
            current += glyph
            end = if (current.size == 1) glyph.x + glyph.advance else max(end, glyph.x + glyph.advance)
        }
        segment(current)?.let(segments::add)
        return segments
    }

    private fun segment(glyphs: List<Glyph>): TextSegment? {
        val raw = StringBuilder()
        var end = Double.NEGATIVE_INFINITY
        for ((index, glyph) in glyphs.withIndex()) {
            if (index > 0 && glyph.x - end > SPACE_GAP * max(glyph.size, glyphs[index - 1].size)) raw.append(' ')
            raw.append(glyph.text)
            end = if (index == 0) glyph.x + glyph.advance else max(end, glyph.x + glyph.advance)
        }
        val text = TicketText.clean(raw.toString())
        if (text.isEmpty()) return null
        // Not cleaned one by one: two glyphs may map to the halves of one surrogate pair.
        val visible = glyphs.filter { glyph -> glyph.text.any(::isVisible) }.ifEmpty { glyphs }
        return TextSegment(
            x = visible.first().x,
            y = visible.first().y,
            size = visible.maxOf { it.size },
            isBold = visible.all { it.isBold },
            text = text,
        )
    }

    private fun isVisible(char: Char): Boolean =
        !char.isWhitespace() && !Character.isISOControl(char) && Character.getType(char) != Character.FORMAT.toInt()

    /** At least this many points of baseline tolerance, for tiny fonts. */
    private const val MIN_TOLERANCE = 0.5
}
