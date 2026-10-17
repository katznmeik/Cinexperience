package io.github.katznmeik.cinexperience.core.ticket.text

import io.github.katznmeik.cinexperience.core.ticket.TicketPdfError
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfLimits
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfDocument
import io.github.katznmeik.cinexperience.core.ticket.pdf.TicketPdfException
import io.github.katznmeik.cinexperience.core.ticket.testing.PdfBuilder
import io.github.katznmeik.cinexperience.core.ticket.testing.PdfBuilder.Companion.latin1
import io.github.katznmeik.cinexperience.core.ticket.testing.TestFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentInterpreterTest {

    private val font = TestFont("F1", "ArialMT")
    private val boldFont = TestFont("F2", "Arial-BoldMT")

    private fun hex(text: String, f: TestFont = font) = "<" + text.map { f.hex(it).removeSurrounding("<", ">") }.joinToString("") + ">"

    /** Runs [content] on a page with the fonts F1, F2, F9 (a Type 1 font), the image Im1 and the form Fm1. */
    private fun run(content: String, budget: GlyphBudget = GlyphBudget()): PageContent {
        val builder = PdfBuilder()
        val catalog = builder.reserve()
        val node = builder.reserve()
        val regular = font.addTo(builder)
        val bold = boldFont.addTo(builder)
        val type1 = builder.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        val image = builder.addStream("/Type /XObject /Subtype /Image /Width 2 /Height 2 /BitsPerComponent 8 /ColorSpace /DeviceGray", ByteArray(4))
        val form = builder.addStream("/Type /XObject /Subtype /Form /BBox [0 0 1 1]", "BT /F1 12 Tf ${hex("X")} Tj ET".latin1())
        val stream = builder.addStream("", content.latin1())
        val page = builder.add(
            "<< /Type /Page /Parent $node 0 R /Contents $stream 0 R /Resources << /Font << /F1 $regular 0 R /F2 $bold 0 R /F9 $type1 0 R >> " +
                "/XObject << /Im1 $image 0 R /Fm1 $form 0 R >> >> >>",
        )
        builder.set(catalog, "<< /Type /Catalog /Pages $node 0 R >>")
        builder.set(node, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
        val document = PdfDocument.open(builder.build(catalog))
        val pdfPage = document.pages().single()
        val data = document.decodedData(document.contentStreams(pdfPage).single())
        return ContentInterpreter(document, pdfPage.resources, FontCache(document), budget).run(data)
    }

    private fun texts(content: PageContent) = content.glyphs.joinToString("") { it.text }

    private fun error(block: () -> Unit): TicketPdfError? = try {
        block()
        null
    } catch (e: TicketPdfException) {
        e.error
    }

    @Test
    fun placesQtsGlyphByGlyphTextOnAFlippedPage() {
        val content = run("1 0 0 -1 0 1191 cm q BT /F1 12 Tf 1 0 0 -1 0 0 Tm 56 -379 Td ${hex("A")} Tj 8 0 Td ${hex("b")} Tj ET Q")

        assertEquals("Ab", texts(content))
        val (a, b) = content.glyphs
        assertEquals(56.0, a.x, 1e-9)
        assertEquals(812.0, a.y, 1e-9)
        assertEquals(12.0, a.size, 1e-9)
        assertEquals(667 * 12 / 1000.0, a.advance, 1e-9)
        assertEquals(64.0, b.x, 1e-9)
        assertEquals(812.0, b.y, 1e-9)
    }

    @Test
    fun aStringOfSeveralGlyphsAdvancesByTheirWidths() {
        val content = run("BT /F1 10 Tf 100 200 Td ${hex("Ab c")} Tj ET")

        assertEquals("Ab\tc", texts(content))
        assertEquals(listOf(100.0, 106.67, 112.23, 115.01), content.glyphs.map { Math.round(it.x * 100) / 100.0 })
        assertTrue(content.glyphs.all { it.y == 200.0 && !it.isBold })
    }

    @Test
    fun tjNumbersMoveTheNextGlyph() {
        val content = run("BT /F2 10 Tf 0 0 Td [${hex("A")} -1000 ${hex("B")} 500 ${hex("C")}] TJ ET")

        assertEquals("ABC", texts(content))
        assertEquals(listOf(0.0, 17.22, 19.44), content.glyphs.map { Math.round(it.x * 100) / 100.0 })
        assertTrue(content.glyphs.all { it.isBold })
    }

    @Test
    fun lineOperatorsMoveDownByTheLeading() {
        val content = run(
            "BT /F1 10 Tf 14 TL 50 500 Td ${hex("a")} Tj T* ${hex("b")} Tj ${hex("c")} ' 1 2 ${hex("d")} \" 0 -20 TD ${hex("e")} Tj T* ${hex("f")} Tj ET",
        )

        assertEquals("abcdef", texts(content))
        assertEquals(listOf(500.0, 486.0, 472.0, 458.0, 438.0, 418.0), content.glyphs.map { it.y })
        assertTrue(content.glyphs.all { it.x == 50.0 })
    }

    @Test
    fun graphicsStatesAreRestoredAndScaleTheText() {
        val content = run("q 2 0 0 2 10 10 cm BT /F1 10 Tf 5 5 Td ${hex("a")} Tj ET Q BT /F1 10 Tf 5 5 Td ${hex("b")} Tj ET")

        val (a, b) = content.glyphs
        assertEquals(20.0, a.x, 1e-9)
        assertEquals(20.0, a.size, 1e-9)
        assertEquals(5.0, b.x, 1e-9)
        assertEquals(10.0, b.size, 1e-9)
    }

    @Test
    fun skipsOperatorsItDoesNotNeedAndInlineImages() {
        val content = run(
            "/GSa gs 1 0 0 rg 0 0 10 10 re f /P << /MCID 0 >> BDC EMC xyz 1 2 3 4 5 6 7 8 9 sc " +
                "BI /W 1 /H 1 /CS /G /BPC 8 ID \u0001\u00ff EI " +
                "BT /F1 10 Tf 0 0 Td ${hex("ok")} Tj ET",
        )

        assertEquals("ok", texts(content))
    }

    @Test
    fun dropsTextItCantMapAndTextOutsideTextObjects() {
        val content = run(
            "BT /F9 10 Tf 0 0 Td ${hex("type1")} Tj /F7 10 Tf ${hex("none")} Tj /F1 10 Tf <FFFF0004> Tj ET ${hex("out")} Tj",
        )

        // <FFFF> has no mapping (dropped), <0004> is the font's second glyph ('!').
        assertEquals("!", texts(content))
        assertEquals(1000 * 10 / 1000.0, content.glyphs.single().x, 1e-9)
    }

    @Test
    fun dropsTextThatIsntUpright() {
        val content = run("BT /F1 10 Tf 0 1 -1 0 100 100 Tm ${hex("r")} Tj 1 0 0 -1 0 0 Tm ${hex("m")} Tj 1 0 0.5 1 0 0 Tm ${hex("s")} Tj -1 0 0 1 0 0 Tm ${hex("x")} Tj 1 0 0 1 0 0 Tm ${hex("u")} Tj ET")

        assertEquals("u", texts(content))
    }

    @Test
    fun recordsDrawnImagesButDoesNotFollowForms() {
        val content = run("q 150 0 0 150 56 845 cm /Im1 Do Q /Fm1 Do /Missing Do")

        assertEquals("", texts(content))
        assertEquals(1, content.images.size)
        assertEquals(150.0, content.images.single().placement.a, 1e-9)
        assertEquals(845.0, content.images.single().placement.f, 1e-9)
    }

    @Test
    fun malformedOperandsAreSkipped() {
        val content = run("BT /F1 Tf 10 /F1 Tf (x) 0 Td 1 2 3 Tm 0 0 Td ${hex("a")} Tj /F1 10 Tf 0 0 Td ${hex("b")} Tj ET Q Q Q")

        assertEquals("b", texts(content))
    }

    @Test
    fun anImageDrawnOftenIsRecordedOnce() {
        assertEquals(1, run("q 1 0 0 1 0 0 cm /Im1 Do Q ".repeat(10_000)).images.size)
    }

    @Test(timeout = 10_000)
    fun eachFontAndCMapIsBuiltOnceHoweverOftenItIsSelected() {
        // 2,000 fonts given directly in the resources, all with one CMap of 4,000 entries,
        // each selected ten times: built every time, this takes minutes.
        val cmap = "4000 beginbfchar\n" + (0 until 4000).joinToString("\n") { "<%04X> <0041>".format(it + 1) } + "\nendbfchar"
        val builder = PdfBuilder()
        val catalog = builder.reserve()
        val node = builder.reserve()
        val toUnicode = builder.addStream("", cmap.latin1())
        val fonts = (0 until 2000).joinToString(" ") { "/G$it << /Type /Font /Subtype /Type0 /BaseFont /ArialMT /Encoding /Identity-H /ToUnicode $toUnicode 0 R >>" }
        val content = "BT 0 0 Td " + (0 until 20_000).joinToString(" ") { "/G${it % 2000} 10 Tf" } + " <0001> Tj ET"
        val stream = builder.addStream("", content.latin1())
        val page = builder.add("<< /Type /Page /Parent $node 0 R /Contents $stream 0 R /Resources << /Font << $fonts >> >> >>")
        builder.set(catalog, "<< /Type /Catalog /Pages $node 0 R >>")
        builder.set(node, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
        val document = PdfDocument.open(builder.build(catalog))
        val pdfPage = document.pages().single()

        val glyphs = ContentInterpreter(document, pdfPage.resources, FontCache(document), GlyphBudget())
            .run(document.decodedData(document.contentStreams(pdfPage).single())).glyphs
        assertEquals("A", glyphs.single().text)
    }

    @Test
    fun atMostTheGlyphLimit() {
        val atLimit = GlyphBudget()
        repeat(TicketPdfLimits.MAX_GLYPHS - 1) { atLimit.take() }
        run("BT /F1 10 Tf 0 0 Td ${hex("a")} Tj ET", atLimit)

        assertEquals(TicketPdfError.TOO_MUCH_TEXT, error { run("BT /F1 10 Tf 0 0 Td ${hex("ab")} Tj ET", GlyphBudget().also { b -> repeat(TicketPdfLimits.MAX_GLYPHS - 1) { b.take() } }) })
    }

    @Test
    fun atMostTheGraphicsStateDepth() {
        val depth = TicketPdfLimits.MAX_GRAPHICS_STATE_DEPTH

        run("q ".repeat(depth) + "Q ".repeat(depth))
        assertEquals(TicketPdfError.NESTING_TOO_DEEP, error { run("q ".repeat(depth + 1)) })
    }
}
