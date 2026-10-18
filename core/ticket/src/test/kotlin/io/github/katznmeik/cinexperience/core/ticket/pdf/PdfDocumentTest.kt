package io.github.katznmeik.cinexperience.core.ticket.pdf

import io.github.katznmeik.cinexperience.core.ticket.TicketPdfError
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfLimits
import io.github.katznmeik.cinexperience.core.ticket.testing.PdfBuilder
import io.github.katznmeik.cinexperience.core.ticket.testing.PdfBuilder.Companion.latin1
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The file structure and its limits (spec §11.2, Security & privacy rule 1). */
class PdfDocumentTest {

    /** Opens [pdf] and unpacks every page's content, like the reader; the error or `null`. */
    private fun refusal(pdf: ByteArray): TicketPdfError? = try {
        val document = PdfDocument.open(pdf)
        document.pages().forEach { page -> document.contentStreams(page).forEach(document::decodedData) }
        null
    } catch (e: TicketPdfException) {
        e.error
    }

    /** A document with [pageCount] empty pages under one Pages node; [extra] adds objects first. */
    private fun pages(pageCount: Int, count: Int = pageCount, trailer: String = "", extra: PdfBuilder.() -> Unit = {}): ByteArray {
        val builder = PdfBuilder()
        val catalog = builder.reserve()
        val node = builder.reserve()
        builder.extra()
        val kids = (1..pageCount).map {
            builder.add("<< /Type /Page /Parent $node 0 R /MediaBox [0 0 842 1191] >>")
        }
        builder.set(catalog, "<< /Type /Catalog /Pages $node 0 R >>")
        builder.set(node, "<< /Type /Pages /Kids [${kids.joinToString(" ") { "$it 0 R" }}] /Count $count >>")
        return builder.build(catalog, trailer = trailer)
    }

    private fun withContent(dictionary: String, data: ByteArray): ByteArray {
        val builder = PdfBuilder()
        val catalog = builder.reserve()
        val node = builder.reserve()
        val stream = builder.addStream(dictionary, data)
        val page = builder.add("<< /Type /Page /Parent $node 0 R /Contents $stream 0 R >>")
        builder.set(catalog, "<< /Type /Catalog /Pages $node 0 R >>")
        builder.set(node, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
        return builder.build(catalog)
    }

    @Test
    fun aFileOfExactlyTheLimitOpensOneByteMoreIsTooLarge() {
        // Padded with an unused stream.
        val padding = TicketPdfLimits.MAX_FILE_BYTES - pages(1).size - 64
        fun padded(extra: Int) = pages(1) { addStream("", ByteArray(padding + extra) { 'x'.code.toByte() }, directLength = true) }
        val probe = padded(0)
        val exact = padded(TicketPdfLimits.MAX_FILE_BYTES - probe.size)

        assertEquals(TicketPdfLimits.MAX_FILE_BYTES, exact.size)
        assertNull(refusal(exact))
        assertEquals(TicketPdfError.TOO_LARGE, refusal(padded(TicketPdfLimits.MAX_FILE_BYTES - probe.size + 1)))
    }

    @Test
    fun aFileThatIsNoPdfIsRefusedAsSuch() {
        for (text in listOf("", "hello", "%PDF", "%PDF-2.0\n", "%PDF-1.x\n", "%!PS-Adobe-3.0\n")) {
            assertEquals(text, TicketPdfError.NOT_A_PDF, refusal(text.latin1()))
        }
    }

    @Test
    fun anEncryptedFileIsRefused() {
        assertEquals(TicketPdfError.ENCRYPTED, refusal(pages(1, trailer = "/Encrypt << /Filter /Standard /V 2 /R 3 >>\n")))
    }

    @Test
    fun crossReferenceStreamsAreUnsupportedOrEncrypted() {
        fun xrefStream(dictionary: String) = ("%PDF-1.5\n1 0 obj\n<< /Type /Catalog >>\nendobj\n2 0 obj\n<< $dictionary /Length 0 >>\nstream\n\nendstream\nendobj\n").let {
            val offset = it.indexOf("2 0 obj")
            "${it}startxref\n$offset\n%%EOF\n".latin1()
        }

        assertEquals(TicketPdfError.UNSUPPORTED, refusal(xrefStream("/Type /XRef /Size 3 /Root 1 0 R /W [1 2 1]")))
        assertEquals(TicketPdfError.ENCRYPTED, refusal(xrefStream("/Type /XRef /Size 3 /Root 1 0 R /Encrypt 5 0 R")))
    }

    @Test
    fun incrementalUpdatesAreUnsupported() {
        assertEquals(TicketPdfError.UNSUPPORTED, refusal(pages(1, trailer = "/Prev 9\n")))
        assertEquals(TicketPdfError.UNSUPPORTED, refusal(pages(1, trailer = "/XRefStm 9\n")))
    }

    @Test
    fun atMostTheObjectLimit() {
        // Catalogue, page tree node and one page: 3 objects plus the padding.
        val atLimit = pages(1) { repeat(TicketPdfLimits.MAX_OBJECTS - 3) { add("null") } }
        val overLimit = pages(1) { repeat(TicketPdfLimits.MAX_OBJECTS - 2) { add("null") } }

        assertNull(refusal(atLimit))
        assertEquals(TicketPdfError.TOO_MANY_OBJECTS, refusal(overLimit))
    }

    @Test
    fun aTrailerSizeOverTheObjectLimitIsRefused() {
        val pdf = String(pages(1), Charsets.ISO_8859_1).replace("/Size 4", "/Size 2002").latin1()

        assertEquals(TicketPdfError.TOO_MANY_OBJECTS, refusal(pdf))
    }

    @Test
    fun atMostThePageLimit() {
        assertNull(refusal(pages(TicketPdfLimits.MAX_PAGES)))
        assertEquals(TicketPdfError.TOO_MANY_PAGES, refusal(pages(TicketPdfLimits.MAX_PAGES + 1)))
        // Also when /Count says otherwise, either way.
        assertEquals(TicketPdfError.TOO_MANY_PAGES, refusal(pages(TicketPdfLimits.MAX_PAGES + 1, count = 1)))
        assertEquals(TicketPdfError.TOO_MANY_PAGES, refusal(pages(1, count = TicketPdfLimits.MAX_PAGES + 1)))
    }

    @Test
    fun aPageTreeThatLoopsIsACycle() {
        val builder = PdfBuilder()
        val catalog = builder.add("<< /Type /Catalog /Pages 2 0 R >>")
        builder.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        builder.add("<< /Type /Pages /Kids [2 0 R] /Count 1 >>")

        assertEquals(TicketPdfError.REFERENCE_CYCLE, refusal(builder.build(catalog)))
    }

    @Test
    fun aPageListedTwiceIsRefused() {
        val builder = PdfBuilder()
        val catalog = builder.add("<< /Type /Catalog /Pages 2 0 R >>")
        builder.add("<< /Type /Pages /Kids [3 0 R 3 0 R] /Count 2 >>")
        builder.add("<< /Type /Page /Parent 2 0 R >>")

        assertEquals(TicketPdfError.REFERENCE_CYCLE, refusal(builder.build(catalog)))
    }

    @Test
    fun aPageTreeDeeperThanTheLimitIsRefused() {
        fun tree(depth: Int): ByteArray {
            val builder = PdfBuilder()
            val catalog = builder.add("<< /Type /Catalog /Pages 2 0 R >>")
            for (level in 0 until depth) builder.add("<< /Type /Pages /Kids [${level + 3} 0 R] /Count 1 >>")
            builder.add("<< /Type /Page >>")
            return builder.build(catalog)
        }

        assertNull(refusal(tree(TicketPdfLimits.MAX_PAGE_TREE_DEPTH)))
        assertEquals(TicketPdfError.NESTING_TOO_DEEP, refusal(tree(TicketPdfLimits.MAX_PAGE_TREE_DEPTH + 1)))
    }

    @Test
    fun deeplyNestedObjectsAreRefused() {
        val nested = "[".repeat(40) + "]".repeat(40)
        val pdf = PdfBuilder.onePage("BT ET", resources = "<< /Deep $nested >>")

        assertEquals(TicketPdfError.NESTING_TOO_DEEP, refusal(pdf))
    }

    @Test
    fun aStreamWhoseLengthRefersToItselfIsACycle() {
        val builder = PdfBuilder()
        val catalog = builder.reserve()
        val node = builder.reserve()
        val stream = builder.reserve()
        builder.set(stream, "<< /Length $stream 0 R >>\nstream\nBT ET\nendstream")
        val page = builder.add("<< /Type /Page /Parent $node 0 R /Contents $stream 0 R >>")
        builder.set(catalog, "<< /Type /Catalog /Pages $node 0 R >>")
        builder.set(node, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")

        assertEquals(TicketPdfError.REFERENCE_CYCLE, refusal(builder.build(catalog)))
    }

    @Test
    fun objectsNeededToReadObjectsMayNotNestDeeply() {
        // Each stream's /Length is the next stream: reading one needs the next, 20 deep.
        val builder = PdfBuilder()
        val catalog = builder.add("<< /Type /Catalog /Pages 2 0 R >>")
        builder.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        builder.add("<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>")
        for (number in 4 until 24) builder.add("<< /Length ${number + 1} 0 R >>\nstream\nBT ET\nendstream")
        builder.add("5")

        assertEquals(TicketPdfError.NESTING_TOO_DEEP, refusal(builder.build(catalog)))
    }

    @Test
    fun aReferenceChainThatLoopsIsACycle() {
        val builder = PdfBuilder()
        val catalog = builder.add("<< /Type /Catalog /Pages 2 0 R >>")
        builder.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        builder.add("<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>")
        builder.add("5 0 R")
        builder.add("4 0 R")

        assertEquals(TicketPdfError.REFERENCE_CYCLE, refusal(builder.build(catalog)))
    }

    @Test
    fun aMissingObjectIsNull() {
        val builder = PdfBuilder()
        val catalog = builder.add("<< /Type /Catalog /Pages 2 0 R >>")
        builder.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        builder.add("<< /Type /Page /Parent 2 0 R /Contents 99 0 R >>")
        val document = PdfDocument.open(builder.build(catalog))

        assertEquals(emptyList<PdfStream>(), document.contentStreams(document.pages().single()))
        assertEquals(PdfNull, document.resolve(PdfReference(99, 0)))
        assertEquals(PdfNull, document.resolve(PdfReference(3, 1)))
    }

    /** [pdf] with its cross-reference table's lines ("xref", "0 4", entries…) changed by [change]. */
    private fun withTable(pdf: String, change: (MutableList<String>) -> Unit): String {
        val start = pdf.lastIndexOf("\nxref\n") + 1
        val end = pdf.indexOf("trailer", start)
        val lines = pdf.substring(start, end).removeSuffix("\n").split("\n").toMutableList()
        change(lines)
        return pdf.substring(0, start) + lines.joinToString("\n") + "\n" + pdf.substring(end)
    }

    @Test
    fun brokenStructureIsDamaged() {
        val good = String(pages(1), Charsets.ISO_8859_1)
        fun lastEntry(change: (String) -> String) = withTable(good) { it[it.lastIndex] = change(it.last()) }
        val variants = mapOf(
            "offset into an object" to lastEntry { String.format(Locale.ROOT, "%010d 00000 n ", it.substring(0, 10).toInt() + 1) },
            "offset beyond the file" to lastEntry { "9999999 00000 n " },
            "bad entry type" to lastEntry { it.replace(" n ", " x ") },
            "wrong generation" to lastEntry { it.replace(" 00000 n ", " 00001 n ") },
            "object number 0 in use" to withTable(good) { it[2] = "0000000009 65535 n " },
            "fewer entries than the subsection says" to withTable(good) { it[1] = "0 5" },
            "object listed twice" to withTable(good) { it[1] = "0 4"; it += "1 1"; it += it[3] },
            "two objects at one offset" to withTable(good) { it[it.lastIndex] = it[it.lastIndex - 1] },
            "no startxref" to good.replace("startxref", "startxrex"),
            "no %%EOF" to good.removeSuffix("%%EOF\n"),
            "startxref beyond the file" to good.replace(Regex("startxref\n\\d+"), "startxref\n99999999"),
            "startxref not at the table" to good.replace(Regex("startxref\n(\\d+)")) { "startxref\n${it.groupValues[1].toInt() - 3}" },
            "no trailer" to good.replace("trailer", "trailex"),
            "no root" to good.replace("/Root", "/Rot"),
            "no endobj" to good.replaceFirst("\nendobj\n", "\nendobx\n"),
        )
        for ((name, pdf) in variants) {
            assertEquals(name, TicketPdfError.DAMAGED, refusal(pdf.latin1()))
        }
    }

    @Test
    fun streamLengthsMustEndAtEndstream() {
        val good = String(withContent("", "BT ET".latin1()), Charsets.ISO_8859_1)
        // The content stream's /Length is the object right after it: "5" (for "BT ET").
        assertNull(refusal(good.latin1()))
        assertEquals(TicketPdfError.DAMAGED, refusal(good.replace("obj\n5\nendobj", "obj\n4\nendobj").latin1()))
        assertEquals(TicketPdfError.DAMAGED, refusal(good.replace("obj\n5\nendobj", "obj\n7\nendobj").latin1()))
        assertEquals(TicketPdfError.DAMAGED, refusal(good.replace("obj\n5\nendobj", "obj\n999999\nendobj").latin1()))
        assertEquals(TicketPdfError.DAMAGED, refusal(good.replace("obj\n5\nendobj", "obj\n-1\nendobj").latin1()))
        assertEquals(TicketPdfError.DAMAGED, refusal(good.replace("obj\n5\nendobj", "obj\n(5)\nendobj").latin1()))
    }

    @Test
    fun streamsUnpackWithFlateOnly() {
        val content = "BT ET".latin1()
        val document = PdfDocument.open(withContent("/Filter [/FlateDecode]", PdfBuilder.deflate(content)))
        assertArrayEquals(content, document.decodedData(document.contentStreams(document.pages().single()).single()))

        assertEquals(TicketPdfError.UNSUPPORTED, refusal(withContent("/Filter /LZWDecode", content)))
        assertEquals(TicketPdfError.UNSUPPORTED, refusal(withContent("/Filter [/ASCIIHexDecode /FlateDecode]", content)))
        assertEquals(TicketPdfError.UNSUPPORTED, refusal(withContent("/Filter /FlateDecode /DecodeParms << /Predictor 12 >>", PdfBuilder.deflate(content))))
        assertEquals(TicketPdfError.UNSUPPORTED, refusal(withContent("/F (content.bin)", content)))
        assertEquals(TicketPdfError.DAMAGED, refusal(withContent("/Filter /FlateDecode", "not compressed".latin1())))
    }

    @Test
    fun aStreamThatUnpacksBeyondTheLimitIsRefused() {
        val atLimit = PdfBuilder.deflate(ByteArray(TicketPdfLimits.MAX_STREAM_BYTES) { ' '.code.toByte() })
        val overLimit = PdfBuilder.deflate(ByteArray(TicketPdfLimits.MAX_STREAM_BYTES + 1) { ' '.code.toByte() })

        assertTrue("A compression bomb: ${overLimit.size} bytes", overLimit.size < 10_000)
        assertNull(refusal(withContent("/Filter /FlateDecode", atLimit)))
        assertEquals(TicketPdfError.STREAM_TOO_LARGE, refusal(withContent("/Filter /FlateDecode", overLimit)))
    }

    @Test
    fun allStreamsTogetherMayUnpackToTheTotalLimitOnly() {
        val chunk = PdfBuilder.deflate(ByteArray(TicketPdfLimits.MAX_STREAM_BYTES) { ' '.code.toByte() })
        fun document(streams: Int): ByteArray {
            val builder = PdfBuilder()
            val catalog = builder.reserve()
            val node = builder.reserve()
            val contents = (1..streams).map { builder.addStream("/Filter /FlateDecode", chunk) }
            val page = builder.add("<< /Type /Page /Parent $node 0 R /Contents [${contents.joinToString(" ") { "$it 0 R" }}] >>")
            builder.set(catalog, "<< /Type /Catalog /Pages $node 0 R >>")
            builder.set(node, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
            return builder.build(catalog)
        }

        assertNull(refusal(document(TicketPdfLimits.MAX_TOTAL_STREAM_BYTES / TicketPdfLimits.MAX_STREAM_BYTES)))
        assertEquals(TicketPdfError.STREAM_TOO_LARGE, refusal(document(TicketPdfLimits.MAX_TOTAL_STREAM_BYTES / TicketPdfLimits.MAX_STREAM_BYTES + 1)))
    }

    @Test
    fun atMostTheContentStreamLimitPerPage() {
        fun document(streams: Int): ByteArray {
            val builder = PdfBuilder()
            val catalog = builder.reserve()
            val node = builder.reserve()
            val content = builder.addStream("", "BT ET".latin1())
            val page = builder.add("<< /Type /Page /Parent $node 0 R /Contents [${List(streams) { "$content 0 R" }.joinToString(" ")}] >>")
            builder.set(catalog, "<< /Type /Catalog /Pages $node 0 R >>")
            builder.set(node, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
            return builder.build(catalog)
        }

        assertNull(refusal(document(TicketPdfLimits.MAX_CONTENT_STREAMS_PER_PAGE)))
        assertEquals(TicketPdfError.UNSUPPORTED, refusal(document(TicketPdfLimits.MAX_CONTENT_STREAMS_PER_PAGE + 1)))
    }

    @Test
    fun resourcesAreInheritedFromThePageTree() {
        val builder = PdfBuilder()
        val catalog = builder.add("<< /Type /Catalog /Pages 2 0 R >>")
        builder.add("<< /Type /Pages /Kids [3 0 R] /Count 1 /Resources << /Font << /F1 9 0 R >> >> >>")
        builder.add("<< /Type /Page /Parent 2 0 R >>")
        val page = PdfDocument.open(builder.build(catalog)).pages().single()

        assertEquals(PdfReference(9, 0), (page.resources?.get("Font") as PdfDictionary)["F1"])
    }
}
