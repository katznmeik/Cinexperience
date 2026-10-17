package io.github.katznmeik.cinexperience.core.ticket

import io.github.katznmeik.cinexperience.core.ticket.pdf.TicketPdfException
import io.github.katznmeik.cinexperience.core.ticket.testing.ImageIoLuminanceDecoder
import io.github.katznmeik.cinexperience.core.ticket.testing.InventedTicketPdf
import io.github.katznmeik.cinexperience.core.ticket.testing.PageSpec
import io.github.katznmeik.cinexperience.core.ticket.testing.PdfBuilder
import io.github.katznmeik.cinexperience.core.ticket.testing.PdfBuilder.Companion.latin1
import io.github.katznmeik.cinexperience.core.ticket.testing.TestFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.random.Random

/**
 * Damaged, cut-off, oversized, encrypted, nested and manipulated files (spec §11.2
 * rule 5): the reader returns a typed refusal, never throws anything else and
 * never hangs. Every limit is also checked end to end here.
 */
class TicketPdfRobustnessTest {

    private val decoder = ImageIoLuminanceDecoder()
    private val reader = TicketPdfReader(decoder)
    private val ticket = InventedTicketPdf.build()
    private val uncompressed = InventedTicketPdf.build(compress = false)

    /** Reads [pdf] without the reader's safety net: only a [TicketPdfException] may come out. */
    private fun strict(pdf: ByteArray): TicketPdfError? = try {
        reader.content(pdf)
        null
    } catch (e: TicketPdfException) {
        e.error
    } catch (e: Exception) {
        throw AssertionError("Unexpected ${e.javaClass.simpleName} (not a typed refusal)", e)
    }

    private fun refusal(pdf: ByteArray): TicketPdfError? = (reader.read(pdf) as? TicketPdfResult.Refused)?.error

    @Test(timeout = 20_000)
    fun aCutOffFileIsDamaged() {
        val step = maxOf(1, ticket.size / 300)
        // Up to the last line ("%%EOF\n"); only its line break may be missing.
        for (length in (0 until ticket.size - 5 step step) + listOf(7, 8, ticket.size - 6, ticket.size - 7)) {
            val error = strict(ticket.copyOf(length))
            val expected = if (length < 8) TicketPdfError.NOT_A_PDF else TicketPdfError.DAMAGED
            assertEquals("cut at $length", expected, error)
        }
    }

    @Test(timeout = 60_000)
    fun randomlyChangedBytesNeverEscapeAsAnythingButARefusal() {
        val random = Random(2026)
        var read = 0
        for (round in 0 until 600) {
            val source = if (round % 2 == 0) ticket else uncompressed
            val pdf = source.copyOf()
            repeat(1 + random.nextInt(12)) {
                val at = random.nextInt(pdf.size)
                pdf[at] = when (random.nextInt(4)) {
                    0 -> random.nextInt(256).toByte()
                    1 -> "()<>[]/%{}".random(random).code.toByte()
                    2 -> "0123456789".random(random).code.toByte()
                    else -> (pdf[at].toInt() xor (1 shl random.nextInt(8))).toByte()
                }
            }
            if (strict(pdf) == null) read++
        }
        assertTrue("Some changes hit nothing the reader looks at", read > 0)
    }

    @Test(timeout = 30_000)
    fun changesInsideTheContentStreamAreSkippedOrRefused() {
        val text = String(uncompressed, Charsets.ISO_8859_1)
        val start = text.indexOf("/GSa gs /CSp cs")
        val length = text.indexOf("endstream", start) - start
        val random = Random(17)
        repeat(300) {
            val pdf = uncompressed.copyOf()
            repeat(1 + random.nextInt(5)) {
                pdf[start + random.nextInt(length)] = "()<>[]/%{}BTEQq0.- \n".random(random).code.toByte()
            }
            strict(pdf)
        }
    }

    @Test(timeout = 10_000)
    fun aFileOverTwoMegabytesIsRefusedFromBytesAndFromAStream() {
        val big = ByteArray(TicketPdfLimits.MAX_FILE_BYTES + 1).also { ticket.copyInto(it) }

        assertEquals(TicketPdfError.TOO_LARGE, refusal(big))
        var served = 0
        val endless = object : InputStream() {
            override fun read(): Int = 0.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                served += len
                return len
            }
        }
        assertEquals(TicketPdfResult.Refused(TicketPdfError.TOO_LARGE), reader.read(endless))
        assertEquals("Reads one byte past the limit, no more", TicketPdfLimits.MAX_FILE_BYTES + 1, served)
        assertTrue(reader.read(ByteArrayInputStream(ticket)) is TicketPdfResult.Read)
    }

    @Test(timeout = 10_000)
    fun anEncryptedTicketIsRefused() {
        val encrypted = String(ticket, Charsets.ISO_8859_1).replace("/Root", "/Encrypt << /Filter /Standard /V 2 /R 3 /O <00> /U <00> /P -4 >>\n/Root").latin1()

        assertEquals(TicketPdfError.ENCRYPTED, refusal(encrypted))
    }

    @Test(timeout = 10_000)
    fun tooManyPagesAreRefused() {
        assertTrue(reader.read(InventedTicketPdf.build(List(TicketPdfLimits.MAX_PAGES) { PageSpec() })) is TicketPdfResult.Read)
        assertEquals(TicketPdfError.TOO_MANY_PAGES, refusal(InventedTicketPdf.build(List(TicketPdfLimits.MAX_PAGES + 1) { PageSpec() })))
    }

    @Test(timeout = 10_000)
    fun tooManyObjectsAreRefused() {
        val table = String(ticket, Charsets.ISO_8859_1).let { it.substring(it.lastIndexOf("\nxref\n")) }
        val objects = Regex("xref\n0 (\\d+)").find(table)!!.groupValues[1].toInt() - 1
        val atLimit = InventedTicketPdf.build(extraObjects = TicketPdfLimits.MAX_OBJECTS - objects)
        val overLimit = InventedTicketPdf.build(extraObjects = TicketPdfLimits.MAX_OBJECTS - objects + 1)

        assertTrue(reader.read(atLimit) is TicketPdfResult.Read)
        assertEquals(TicketPdfError.TOO_MANY_OBJECTS, refusal(overLimit))
        // Only the trailer's count inflated.
        val many = String(ticket, Charsets.ISO_8859_1).replace("/Size ${objects + 1}", "/Size ${TicketPdfLimits.MAX_OBJECTS + 2}").latin1()
        assertEquals(TicketPdfError.TOO_MANY_OBJECTS, refusal(many))
    }

    @Test(timeout = 20_000)
    fun aContentStreamThatUnpacksToMoreThanTwoMegabytesIsRefused() {
        val bomb = InventedTicketPdf.build(mutateContent = { it + " ".repeat(TicketPdfLimits.MAX_STREAM_BYTES) })

        assertTrue("Compressed it stays small: ${bomb.size}", bomb.size < 200_000)
        assertEquals(TicketPdfError.STREAM_TOO_LARGE, refusal(bomb))
    }

    @Test(timeout = 10_000)
    fun deeplyNestedContentIsRefused() {
        val nested = InventedTicketPdf.build(mutateContent = { it + "[".repeat(40) + "]".repeat(40) + " TJ" })
        val states = InventedTicketPdf.build(mutateContent = { it + "q ".repeat(TicketPdfLimits.MAX_GRAPHICS_STATE_DEPTH + 1) })

        assertEquals(TicketPdfError.NESTING_TOO_DEEP, refusal(nested))
        assertEquals(TicketPdfError.NESTING_TOO_DEEP, refusal(states))
    }

    @Test(timeout = 20_000)
    fun tooMuchTextIsRefused() {
        // Upright on the flipped page, so every glyph counts.
        val glyphs = "BT /F11 1 Tf 1 0 0 -1 0 0 Tm 0 0 Td " + "<0004> Tj ".repeat(TicketPdfLimits.MAX_GLYPHS) + "ET"

        assertEquals(TicketPdfError.TOO_MUCH_TEXT, refusal(InventedTicketPdf.build(mutateContent = { it + glyphs })))
    }

    @Test(timeout = 10_000)
    fun thousandsOfLabelsStayCheap() {
        val font = TestFont("F11", "ArialMT")
        fun line(top: Int, text: String) =
            "BT /F11 12 Tf 1 0 0 -1 0 0 Tm 56 -$top Td <${text.map { font.hex(it).removeSurrounding("<", ">") }.joinToString("")}> Tj ET\n"
        val labels = (0 until 2_000).joinToString("") { i -> line(1_200 + i * 15, if (i % 2 == 0) "Buchungsnummer" else "Ticket") }

        val result = reader.read(InventedTicketPdf.build(mutateContent = { "$it$labels" }))

        assertTrue(result is TicketPdfResult.Read)
    }

    @Test(timeout = 10_000)
    fun aReferenceCycleIsRefused() {
        // The page's /Resources points at itself through two objects.
        val builder = PdfBuilder()
        val catalog = builder.add("<< /Type /Catalog /Pages 2 0 R >>")
        builder.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        builder.add("<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources 5 0 R >>")
        builder.addStream("", "BT /F1 12 Tf <0001> Tj ET".latin1(), directLength = true)
        builder.add("6 0 R")
        builder.add("5 0 R")

        assertEquals(TicketPdfError.REFERENCE_CYCLE, refusal(builder.build(catalog)))
    }

    @Test(timeout = 10_000)
    fun atMostEightQrCodeCandidatesAreDecoded() {
        val content = (reader.read(InventedTicketPdf.build(extraQrImages = 20)) as TicketPdfResult.Read).content

        assertEquals(TicketPdfLimits.MAX_QR_CANDIDATES, decoder.sizes.size)
        assertEquals(21, content.qrCode.valueOrNull?.size)
    }

    @Test(timeout = 10_000)
    fun actionsLinksAndAttachmentsAreIgnored() {
        val manipulated = InventedTicketPdf.build(
            catalogExtra = "/OpenAction << /S /JavaScript /JS (app.alert\\(1\\)) >> /AA << /O << /S /Launch /F (calc.exe) >> >> " +
                "/Names << /EmbeddedFiles << /Names [(x) << /Type /Filespec /F (x.exe) >>] >> >> /URI << /Base (https://example.invalid/) >> ",
            pageExtra = "/AA << /O << /S /GoToR /F (other.pdf) /D [0 /Fit] >> >> ",
        )

        val content = (reader.read(manipulated) as TicketPdfResult.Read).content
        assertEquals(TicketPdfField.Found("TEST123"), content.bookingNumber)
        assertEquals(TicketPdfField.Found("delphi LUX"), content.cinema)
    }

    @Test(timeout = 10_000)
    fun aPdfThatIsNoTicketIsRefusedAsSuch() {
        assertEquals(TicketPdfError.NOT_A_TICKET, refusal(PdfBuilder.onePage("BT ET")))
        assertEquals(TicketPdfError.NOT_A_PDF, refusal("<html></html>".latin1()))
    }

    @Test(timeout = 10_000)
    fun aFailingDecoderOnlyCostsTheQrCode() {
        val failing = TicketPdfReader { _, _, _ -> throw IllegalStateException("decoder bug") }
        val content = (failing.read(ticket) as TicketPdfResult.Read).content

        assertEquals(TicketPdfField.NotFound(NotFoundReason.INVALID), content.qrCode)
        assertEquals(TicketPdfField.Found("TEST123"), content.bookingNumber)
    }

    @Test(timeout = 10_000)
    fun theInventedTicketReadsWithoutTheSafetyNet() {
        assertEquals(null, strict(ticket))
        assertEquals(null, strict(uncompressed))
    }
}
