package io.github.katznmeik.cinexperience.core.ticket.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLayoutTest {

    /** Glyphs of [text] from [x] on baseline [y], each [width] em wide plus [gap] em, like Qt's output. */
    private fun word(text: String, x: Double, y: Double, size: Double = 14.0, width: Double = 0.55, gap: Double = 0.004, bold: Boolean = false): List<Glyph> {
        var position = x
        return text.map { char ->
            Glyph(char.toString(), position, y, size, width * size, bold).also { position += (width + gap) * size }
        }
    }

    private fun texts(lines: List<TextLine>) = lines.map { line -> line.segments.map { it.text } }

    @Test
    fun linesRunFromTheTopAndGlyphsFromTheLeft() {
        val glyphs = (word("unten", 10.0, 100.0) + word("oben", 10.0, 500.0)).shuffled(kotlin.random.Random(1))

        assertEquals(listOf(listOf("oben"), listOf("unten")), texts(TextLayout.lines(glyphs)))
    }

    @Test
    fun aLabelItsTabAndItsValueAreOneSegmentWithASpace() {
        // "Saal", a tab item (Yorck maps the space glyph to a tab) and the value 4 pt further on.
        val label = word("Saal", 275.0, 934.0, size = 14.0)
        val tabX = label.last().x + label.last().advance
        val tab = Glyph("\t", tabX, 934.0, 16.0, 4.4, false)
        val value = word("4", tabX + 4.4 + 4.0, 934.0, size = 20.0)

        val line = TextLayout.lines(label + tab + value).single()

        assertEquals(listOf("Saal 4"), line.segments.map { it.text })
        assertEquals(275.0, line.segments.single().x, 1e-9)
        assertEquals(20.0, line.segments.single().size, 1e-9)
    }

    @Test
    fun wideGapsSplitALineIntoSegments() {
        val glyphs = word("Saal 4", 275.0, 934.0) + word("Reihe 7", 501.0, 934.0) + word("Platz 10", 735.0, 934.0)

        assertEquals(listOf(listOf("Saal 4", "Reihe 7", "Platz 10")), texts(TextLayout.lines(glyphs)))
    }

    @Test
    fun aGapOfAboutOneEmStaysInTheSegment() {
        // "Kat." with its value 22 pt (1.1 em at 20 pt) after the tab item.
        val glyphs = word("Kat.", 275.0, 880.0) + word("1 x Normal", 330.0, 880.0, size = 20.0)

        assertEquals(listOf(listOf("Kat. 1 x Normal")), texts(TextLayout.lines(glyphs)))
    }

    @Test
    fun kerningAndTinyGapsAddNoSpace() {
        val glyphs = word("Projectionist", 0.0, 0.0, gap = 0.007) + word("AV", 300.0, 0.0, gap = -0.09)

        assertEquals(listOf(listOf("Projectionist", "AV")), texts(TextLayout.lines(glyphs)))
    }

    @Test
    fun glyphsOnNearlyTheSameBaselineShareALine() {
        val glyphs = word("ab", 0.0, 100.0) + word("cd", 8.5, 100.0 + 0.2 * 14 - 0.01) + word("ef", 0.0, 100.0 - 4.0)

        assertEquals(listOf(listOf("abcd"), listOf("ef")), texts(TextLayout.lines(glyphs)))
    }

    @Test
    fun segmentsAreCleanedAndWhitespaceOnlyOnesDropped() {
        val glyphs = listOf(
            Glyph("\t", 0.0, 10.0, 10.0, 3.0, false),
            Glyph("\u0000", 50.0, 10.0, 10.0, 3.0, false),
            Glyph("\u200b", 100.0, 10.0, 10.0, 3.0, false),
        ) + word("A\u202eB", 200.0, 10.0) + listOf(Glyph("\t", 300.0, 10.0, 10.0, 3.0, false))

        assertEquals(listOf(listOf("AB")), texts(TextLayout.lines(glyphs)))
    }

    @Test
    fun glyphsMappedToTheHalvesOfASurrogatePairStillFormASegment() {
        val glyphs = listOf(Glyph("\ud83c", 0.0, 0.0, 10.0, 5.0, false), Glyph("\udfac", 5.0, 0.0, 10.0, 5.0, false))

        assertEquals(listOf(listOf("\ud83c\udfac")), texts(TextLayout.lines(glyphs)))
    }

    @Test
    fun aSegmentIsBoldOnlyIfAllItsVisibleGlyphsAre() {
        val mixed = word("Ab", 0.0, 100.0, bold = true) + word("c", 15.5, 100.0)
        val bold = word("Title", 0.0, 0.0, bold = true) + Glyph("\t", 40.0, 0.0, 14.0, 3.0, false)

        val (mixedLine, boldLine) = TextLayout.lines(bold + mixed)
        assertEquals("Abc", mixedLine.segments.single().text)
        assertFalse(mixedLine.segments.single().isBold)
        assertEquals("Title", boldLine.segments.single().text)
        assertTrue(boldLine.segments.single().isBold)
    }
}
