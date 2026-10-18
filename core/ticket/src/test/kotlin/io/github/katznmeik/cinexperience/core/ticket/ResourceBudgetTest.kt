package io.github.katznmeik.cinexperience.core.ticket

import io.github.katznmeik.cinexperience.core.ticket.testing.ImageIoLuminanceDecoder
import io.github.katznmeik.cinexperience.core.ticket.testing.PdfBuilder
import io.github.katznmeik.cinexperience.core.ticket.testing.PdfBuilder.Companion.latin1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.util.Locale

/**
 * The limits count work, not only distinct items: crafted files that reuse one
 * object many times (shared page tree arrays, overlapping objects, shared width
 * arrays, repeated content streams, repeated CMap ranges) are refused quickly and
 * without much memory.
 */
class ResourceBudgetTest {

    private val reader = TicketPdfReader(ImageIoLuminanceDecoder())

    private fun refusal(pdf: ByteArray): TicketPdfError? = (reader.read(pdf) as? TicketPdfResult.Refused)?.error

    /** Bytes this thread allocates while running [block]. */
    private fun allocated(block: () -> Unit): Long {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = threads.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }

    /** One page with [contents] (a reference or an array of them) and [resources]. */
    private fun page(builder: PdfBuilder, contents: String, resources: String = "<< >>"): ByteArray {
        val catalog = builder.reserve()
        val node = builder.reserve()
        val page = builder.add("<< /Type /Page /Parent $node 0 R /Contents $contents /Resources $resources >>")
        builder.set(node, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
        builder.set(catalog, "<< /Type /Catalog /Pages $node 0 R >>")
        return builder.build(catalog)
    }

    @Test(timeout = 10_000)
    fun aPageTreeThatReusesItsKidsArraysIsRefused() {
        // Ten levels of ten direct nodes, each level's nodes sharing the next level's /Kids array
        // and no page at the bottom: 10^8 nodes to visit, through 10 objects.
        val builder = PdfBuilder()
        val catalog = builder.reserve()
        val levels = (1..9).map { builder.reserve() }
        levels.forEachIndexed { index, number ->
            val next = levels.getOrNull(index + 1)
            builder.set(number, if (next == null) "[]" else "[" + "<< /Type /Pages /Kids $next 0 R >> ".repeat(10) + "]")
        }
        val root = builder.add("<< /Type /Pages /Kids ${levels.first()} 0 R >>")
        builder.set(catalog, "<< /Type /Catalog /Pages $root 0 R >>")

        assertEquals(TicketPdfError.TOO_MANY_PAGES, refusal(builder.build(catalog)))
    }

    @Test(timeout = 20_000)
    fun objectsThatOverlapAreReadOnlyUpToTheNextObject() {
        // 1,500 objects nested in each other's strings, each holding all following ones:
        // read from their offsets to their own end, the file would be lexed and kept 1,500 times.
        val count = 1_500
        val first = 8
        val pad = "x".repeat(1_000)
        val out = ByteArrayOutputStream()
        val offsets = HashMap<Int, Int>()
        fun write(text: String) = out.write(text.latin1())
        write("%PDF-1.4\n")
        val cmap = "begincmap 1 beginbfchar <0001> <0041> endbfchar endcmap"
        val text = "BT /F1 12 Tf <0001> Tj ET"
        val widths = (first until first + count).joinToString(" ") { "$it 0 R" }
        val head = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${text.length} >>\nstream\n$text\nendstream",
            "<< /Type /Font /Subtype /Type0 /BaseFont /ArialMT /Encoding /Identity-H /ToUnicode 6 0 R /DescendantFonts [7 0 R] >>",
            "<< /Length ${cmap.length} >>\nstream\n$cmap\nendstream",
            "<< /Type /Font /Subtype /CIDFontType2 /W [0 [$widths]] >>",
        )
        head.forEachIndexed { i, body ->
            offsets[i + 1] = out.size()
            write("${i + 1} 0 obj\n$body\nendobj\n")
        }
        for (number in first until first + count) {
            offsets[number] = out.size()
            write("$number 0 obj\n($pad")
        }
        repeat(count) { write(")\nendobj\n") }
        val xref = out.size()
        write("xref\n0 ${first + count}\n0000000000 65535 f \n")
        for (number in 1 until first + count) write(String.format(Locale.ROOT, "%010d 00000 n \n", offsets.getValue(number)))
        write("trailer\n<< /Size ${first + count} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        val pdf = out.toByteArray()
        assertTrue(pdf.size < TicketPdfLimits.MAX_FILE_BYTES)

        var error: TicketPdfError? = null
        val bytes = allocated { error = refusal(pdf) }

        assertEquals(TicketPdfError.DAMAGED, error)
        assertTrue("Allocated ${bytes / 1_000_000} MB", bytes < 64_000_000)
    }

    @Test(timeout = 20_000)
    fun aWidthArraySharedByManyFontsIsReadOnce() {
        // The most fonts a document may load, given directly in the resources, all with one
        // /W array of 9,000 widths: read for each font, they would exceed the width limit.
        val count = TicketPdfLimits.MAX_FONTS
        assertTrue(count * 9_000 > TicketPdfLimits.MAX_GLYPH_WIDTHS)
        val builder = PdfBuilder()
        val widths = builder.add("[0 [" + "500 ".repeat(9_000) + "]]")
        val toUnicode = builder.addStream("", "begincmap 1 beginbfchar <0001> <0041> endbfchar endcmap".latin1(), directLength = true)
        val fonts = (0 until count).joinToString(" ") {
            "/G$it << /Type /Font /Subtype /Type0 /BaseFont /ArialMT /Encoding /Identity-H /ToUnicode $toUnicode 0 R /DescendantFonts [<< /W $widths 0 R >>] >>"
        }
        val content = builder.addStream("", ("BT 0 0 Td " + (0 until count).joinToString(" ") { "/G$it 10 Tf <0001> Tj" } + " ET").latin1())

        var error: TicketPdfError? = null
        val bytes = allocated { error = refusal(page(builder, "$content 0 R", "<< /Font << $fonts >> >>")) }

        assertEquals(TicketPdfError.NOT_A_TICKET, error)
        assertTrue("Allocated ${bytes / 1_000_000} MB", bytes < 32_000_000)
    }

    @Test(timeout = 10_000)
    fun moreGlyphWidthsThanAnyTicketHasAreRefused() {
        val builder = PdfBuilder()
        val toUnicode = builder.addStream("", "begincmap 1 beginbfchar <0001> <0041> endbfchar endcmap".latin1(), directLength = true)
        val font = "<< /Type /Font /Subtype /Type0 /BaseFont /ArialMT /Encoding /Identity-H /ToUnicode $toUnicode 0 R " +
            "/DescendantFonts [<< /W [0 65535 500 0 65535 600] >>] >>"
        val content = builder.addStream("", "BT /F1 10 Tf <0001> Tj ET".latin1())

        assertEquals(TicketPdfError.UNSUPPORTED, refusal(page(builder, "$content 0 R", "<< /Font << /F1 $font >> >>")))
    }

    @Test(timeout = 10_000)
    fun everyShownCodeCountsAgainstTheGlyphLimitMappedOrNot() {
        val builder = PdfBuilder()
        val toUnicode = builder.addStream("", "begincmap 1 beginbfchar <0001> <0041> endbfchar endcmap".latin1(), directLength = true)
        val font = "<< /Type /Font /Subtype /Type0 /BaseFont /ArialMT /Encoding /Identity-H /ToUnicode $toUnicode 0 R >>"
        val unmapped = "FFFF".repeat(TicketPdfLimits.MAX_GLYPHS + 1)
        val content = builder.addFlateStream("", "BT /F1 10 Tf 0 0 Td <$unmapped> Tj ET".latin1())

        assertEquals(TicketPdfError.TOO_MUCH_TEXT, refusal(page(builder, "$content 0 R", "<< /Font << /F1 $font >> >>")))
    }

    @Test(timeout = 20_000)
    fun aContentStreamListedOverAndOverCountsEachTime() {
        // One stream of 2 MB (a few KB packed), listed 32 times: 64 MB to interpret.
        val builder = PdfBuilder()
        val stream = builder.addFlateStream("", ByteArray(TicketPdfLimits.MAX_STREAM_BYTES) { ' '.code.toByte() })
        val pdf = page(builder, "[" + "$stream 0 R ".repeat(TicketPdfLimits.MAX_CONTENT_STREAMS_PER_PAGE) + "]")

        var error: TicketPdfError? = null
        val bytes = allocated { error = refusal(pdf) }

        assertEquals(TicketPdfError.STREAM_TOO_LARGE, error)
        assertTrue("Allocated ${bytes / 1_000_000} MB", bytes < 48_000_000)
    }

    @Test(timeout = 20_000)
    fun pagesSharingContentStreamsCountEachTime() {
        // Two distinct 2 MB streams on each of 4 pages: 4 MB unpacked, 16 MB to interpret.
        val builder = PdfBuilder()
        val first = builder.addFlateStream("", ByteArray(TicketPdfLimits.MAX_STREAM_BYTES) { ' '.code.toByte() })
        val second = builder.addFlateStream("", ByteArray(TicketPdfLimits.MAX_STREAM_BYTES) { '\n'.code.toByte() })
        val catalog = builder.reserve()
        val node = builder.reserve()
        val pages = (1..4).map { builder.add("<< /Type /Page /Parent $node 0 R /Contents [$first 0 R $second 0 R] >>") }
        builder.set(node, "<< /Type /Pages /Kids [${pages.joinToString(" ") { "$it 0 R" }}] /Count 4 >>")
        builder.set(catalog, "<< /Type /Catalog /Pages $node 0 R >>")

        var error: TicketPdfError? = null
        val bytes = allocated { error = refusal(builder.build(catalog)) }

        assertEquals(TicketPdfError.STREAM_TOO_LARGE, error)
        assertTrue("Allocated ${bytes / 1_000_000} MB", bytes < 64_000_000)
    }

    @Test(timeout = 5_000)
    fun cmapRangesRepeatedOverTheSameCodesAreRefused() {
        // 360,000 ranges over the same 256 codes in 4 CMaps (1.9 MB each, packed small): 92 M codes to process.
        val builder = PdfBuilder()
        val ranges = "90000 beginbfrange\n" + "<0000> <00FF> <0041>\n".repeat(90_000) + "endbfrange"
        val fonts = (0 until 4).joinToString(" ") {
            val cmap = builder.addFlateStream("", "begincmap $ranges endcmap".latin1())
            "/F$it << /Type /Font /Subtype /Type0 /BaseFont /ArialMT /Encoding /Identity-H /ToUnicode $cmap 0 R >>"
        }
        val content = builder.addStream("", ("BT 0 0 Td " + (0 until 4).joinToString(" ") { "/F$it 10 Tf <0001> Tj" } + " ET").latin1())

        assertEquals(TicketPdfError.UNSUPPORTED, refusal(page(builder, "$content 0 R", "<< /Font << $fonts >> >>")))
    }

    @Test(timeout = 20_000)
    fun oneOperandSpreadOverFourContentStreamsIsRefused() {
        // Four content streams of almost 2 MB each (2 KB packed) that together hold one operand:
        // an array of 838 arrays of 9,999 empty names, 8.4 million values.
        val inner = "[" + "/".repeat(9_999) + "]"
        val text = ("[" + inner.repeat(838) + "]").latin1()
        val quarter = (text.size + 3) / 4
        assertTrue(quarter < TicketPdfLimits.MAX_STREAM_BYTES)
        val builder = PdfBuilder()
        val streams = (0 until 4).map { builder.addFlateStream("", text.copyOfRange(it * quarter, minOf(text.size, (it + 1) * quarter))) }
        val pdf = page(builder, streams.joinToString(" ", "[", "]") { "$it 0 R" })
        assertTrue(pdf.size < 16_000)

        var error: TicketPdfError? = null
        val bytes = allocated { error = refusal(pdf) }

        assertEquals(TicketPdfError.UNSUPPORTED, error)
        assertTrue("Allocated ${bytes / 1_000_000} MB", bytes < 64_000_000)
    }

    @Test(timeout = 20_000)
    fun aHugeEntryOfThePageDictionaryIsRefused() {
        // 1.9 MB of empty names in an entry of the page dictionary, which the object cache would keep.
        val inner = "[" + "/".repeat(9_999) + "]"
        val builder = PdfBuilder()
        val catalog = builder.reserve()
        val node = builder.reserve()
        val content = builder.addStream("", "BT ET".latin1())
        val page = builder.add("<< /Type /Page /Parent $node 0 R /Contents $content 0 R /Extra [${inner.repeat(190)}] >>")
        builder.set(node, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
        builder.set(catalog, "<< /Type /Catalog /Pages $node 0 R >>")
        val pdf = builder.build(catalog)
        assertTrue(pdf.size < TicketPdfLimits.MAX_FILE_BYTES)

        var error: TicketPdfError? = null
        val bytes = allocated { error = refusal(pdf) }

        assertEquals(TicketPdfError.TOO_MANY_OBJECTS, error)
        assertTrue("Allocated ${bytes / 1_000_000} MB", bytes < 48_000_000)
    }

    @Test(timeout = 10_000)
    fun valuesInContentStreamsCountForTheWholeDocument() {
        // Operands no operator takes are dropped at once, yet each counts.
        val builder = PdfBuilder()
        val content = builder.addFlateStream("", "0 ".repeat(TicketPdfLimits.MAX_PARSED_VALUES).latin1())

        assertEquals(TicketPdfError.TOO_MANY_OBJECTS, refusal(page(builder, "$content 0 R")))
    }

    /** [count] fonts given directly in the resources, each with its own packed CMap of [cmap], all selected once. */
    private fun fonts(count: Int, cmap: String): ByteArray {
        val builder = PdfBuilder()
        val packed = PdfBuilder.deflate("begincmap $cmap endcmap".latin1())
        val fonts = (0 until count).joinToString(" ") {
            val stream = builder.addStream("/Filter /FlateDecode", packed, directLength = true)
            "/F$it << /Type /Font /Subtype /Type0 /BaseFont /ArialMT /Encoding /Identity-H /ToUnicode $stream 0 R >>"
        }
        val content = builder.addStream("", ("BT " + (0 until count).joinToString(" ") { "/F$it 1 Tf" } + " ET").latin1())
        return page(builder, "$content 0 R", "<< /Font << $fonts >> >>")
    }

    /** 16 ranges of 256 codes, each mapped to 8 UTF-16 units: a CMap of 4,096 long entries. */
    private val fullCMap = "16 beginbfrange\n" +
        (0 until 16).joinToString("\n") { "<%02X00> <%02XFF> <00410042004300440045004600470048>".format(it, it) } + "\nendbfrange"

    @Test(timeout = 20_000)
    fun manyFontsWithTheirOwnCMapsAreRefused() {
        // 1,980 fonts, each with its own 4,096-entry CMap of a few hundred bytes: 8 M codes, a gigabyte of tables.
        val pdf = fonts(1_980, fullCMap)
        assertTrue(pdf.size < TicketPdfLimits.MAX_FILE_BYTES)

        var error: TicketPdfError? = null
        val bytes = allocated { error = refusal(pdf) }

        assertEquals(TicketPdfError.UNSUPPORTED, error)
        assertTrue("Allocated ${bytes / 1_000_000} MB", bytes < 32_000_000)
    }

    @Test(timeout = 10_000)
    fun aDocumentLoadsAtMostSixteenFonts() {
        val cmap = "1 beginbfchar <0001> <0041> endbfchar"

        assertEquals(TicketPdfError.NOT_A_TICKET, refusal(fonts(TicketPdfLimits.MAX_FONTS, cmap)))
        assertEquals(TicketPdfError.UNSUPPORTED, refusal(fonts(TicketPdfLimits.MAX_FONTS + 1, cmap)))
    }

    @Test(timeout = 10_000)
    fun cmapCodesCountForTheWholeDocument() {
        // Each CMap is within its own limit; four of them reach the document's.
        val count = TicketPdfLimits.MAX_CMAP_CODES / 4_096

        assertEquals(TicketPdfError.NOT_A_TICKET, refusal(fonts(count, fullCMap)))
        assertEquals(TicketPdfError.UNSUPPORTED, refusal(fonts(count + 1, fullCMap)))
    }
}
