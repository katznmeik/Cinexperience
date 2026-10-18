package io.github.katznmeik.cinexperience.core.ticket

import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfDocument
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfStream
import io.github.katznmeik.cinexperience.core.ticket.pdf.TicketPdfException
import io.github.katznmeik.cinexperience.core.ticket.pdf.refuse
import io.github.katznmeik.cinexperience.core.ticket.qr.BitmapLuminanceDecoder
import io.github.katznmeik.cinexperience.core.ticket.qr.LuminanceDecoder
import io.github.katznmeik.cinexperience.core.ticket.qr.QrGridReader
import io.github.katznmeik.cinexperience.core.ticket.text.ContentInterpreter
import io.github.katznmeik.cinexperience.core.ticket.text.FontCache
import io.github.katznmeik.cinexperience.core.ticket.text.GlyphBudget
import io.github.katznmeik.cinexperience.core.ticket.text.TextLayout
import io.github.katznmeik.cinexperience.core.ticket.text.TextLine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads a Yorck ticket PDF (spec §11.2) with the app's own small reader: no PDF, OCR
 * or QR-code library, no network, nothing stored. The file is untrusted; it is
 * refused ([TicketPdfResult.Refused]) beyond [TicketPdfLimits] or outside the
 * structure Yorck's ticket system writes, and the reader never runs scripts,
 * follows links or loads anything the file refers to. Every field is checked
 * ([TicketPdfRules]); the QR code is read as a module grid only ([QrModuleGrid]).
 *
 * Blocking and CPU-bound (well under a second for a real ticket): call it off the
 * main thread. It never throws for any file content.
 */
class TicketPdfReader internal constructor(private val decoder: LuminanceDecoder) {
    /** The reader with Android's JPEG decoder. */
    constructor() : this(BitmapLuminanceDecoder)

    /** Reads at most [TicketPdfLimits.MAX_FILE_BYTES] + 1 bytes of [input] (more is [TicketPdfError.TOO_LARGE]); doesn't close it. */
    @Throws(IOException::class)
    fun read(input: InputStream): TicketPdfResult {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() <= TicketPdfLimits.MAX_FILE_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, TicketPdfLimits.MAX_FILE_BYTES + 1 - out.size()))
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        if (out.size() > TicketPdfLimits.MAX_FILE_BYTES) return TicketPdfResult.Refused(TicketPdfError.TOO_LARGE)
        return read(out.toByteArray())
    }

    fun read(bytes: ByteArray): TicketPdfResult = try {
        TicketPdfResult.Read(content(bytes))
    } catch (e: TicketPdfException) {
        TicketPdfResult.Refused(e.error)
    } catch (e: OutOfMemoryError) {
        TicketPdfResult.Refused(TicketPdfError.TOO_LARGE)
    } catch (e: StackOverflowError) {
        TicketPdfResult.Refused(TicketPdfError.NESTING_TOO_DEEP)
    } catch (e: RuntimeException) {
        // A shape no check foresaw: refused, never a crash.
        TicketPdfResult.Refused(TicketPdfError.DAMAGED)
    }

    /** [read] without its safety net: throws only [TicketPdfException] for a refused file (tests check that nothing else escapes). */
    internal fun content(bytes: ByteArray): TicketPdfContent {
        val document = PdfDocument.open(bytes)
        val fonts = FontCache(document)
        val budget = GlyphBudget()
        val lines = ArrayList<List<TextLine>>()
        val images = ArrayList<PdfStream>()
        var interpreted = 0L
        for (page in document.pages()) {
            val streams = document.contentStreams(page).map(document::decodedData)
            // A stream listed again (on this page or another) is interpreted again: it counts each time.
            interpreted += streams.sumOf { it.size.toLong() + 1 }
            if (interpreted > TicketPdfLimits.MAX_TOTAL_STREAM_BYTES) {
                refuse(TicketPdfError.STREAM_TOO_LARGE, "Pages interpret more than ${TicketPdfLimits.MAX_TOTAL_STREAM_BYTES} bytes of content")
            }
            val drawn = ContentInterpreter(document, page.resources, fonts, budget).run(joined(streams))
            lines += TextLayout.lines(drawn.glyphs)
            images += drawn.images
        }
        val fields = TicketFields.read(lines) ?: refuse(TicketPdfError.NOT_A_TICKET, "No ticket fields")
        return TicketPdfContent(
            bookingNumber = fields.bookingNumber,
            filmTitle = fields.filmTitle,
            cinema = fields.cinema,
            date = fields.date,
            startTime = fields.startTime,
            hall = fields.hall,
            seats = fields.seats,
            qrCode = qrCode(document, images),
        )
    }

    /** The one QR code grid among the drawn images, if exactly one distinct grid checks out. */
    private fun qrCode(document: PdfDocument, images: List<PdfStream>): TicketPdfField<QrModuleGrid> {
        val candidates = images.distinctBy { it.objectNumber }
            .filter { isCandidate(document, it) }
            .take(TicketPdfLimits.MAX_QR_CANDIDATES)
        if (candidates.isEmpty()) return TicketPdfField.NotFound(NotFoundReason.MISSING)
        val grids = candidates.mapNotNull { grid(document, it) }.distinct()
        return when (grids.size) {
            0 -> TicketPdfField.NotFound(NotFoundReason.INVALID)
            1 -> TicketPdfField.Found(grids.single())
            else -> TicketPdfField.NotFound(NotFoundReason.AMBIGUOUS)
        }
    }

    /**
     * A JPEG (DCTDecode only) of 8-bit RGB or grey, from 21 to [TicketPdfLimits.MAX_IMAGE_SIDE]
     * px each way and at most [TicketPdfLimits.MAX_QR_IMAGE_BYTES] as stored.
     */
    private fun isCandidate(document: PdfDocument, stream: PdfStream): Boolean {
        if (stream.dataLength > TicketPdfLimits.MAX_QR_IMAGE_BYTES) return false
        val dictionary = stream.dictionary
        val width = document.int(dictionary["Width"]) ?: return false
        val height = document.int(dictionary["Height"]) ?: return false
        val sides = QrModuleGrid.QR_SIZES.first()..TicketPdfLimits.MAX_IMAGE_SIDE
        return width in sides && height in sides &&
            document.filters(stream) == listOf("DCTDecode") &&
            document.int(dictionary["BitsPerComponent"]) == 8 &&
            document.name(dictionary["ColorSpace"]) in setOf("DeviceRGB", "DeviceGray") &&
            listOf("ImageMask", "F", "FFilter").none(dictionary::containsKey)
    }

    private fun grid(document: PdfDocument, stream: PdfStream): QrModuleGrid? {
        val jpeg = document.rawData(stream)
        if (jpeg.size < 3 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte() || jpeg[2] != 0xFF.toByte()) return null
        val width = document.int(stream.dictionary["Width"]) ?: return null
        val height = document.int(stream.dictionary["Height"]) ?: return null
        val image = try {
            decoder.decode(jpeg, width, height)
        } catch (e: RuntimeException) {
            null
        } ?: return null
        if (image.width != width || image.height != height) return null
        return QrGridReader.read(image)
    }

    /**
     * The page's streams one after another, each followed by a line feed: a stream ends
     * a token, but an operand or operation may go on in the next stream (ISO 32000-1, 7.8.2).
     */
    private fun joined(streams: List<ByteArray>): ByteArray {
        if (streams.size == 1) return streams.single()
        val out = ByteArray(streams.sumOf { it.size + 1 })
        var at = 0
        for (stream in streams) {
            stream.copyInto(out, at)
            at += stream.size
            out[at++] = '\n'.code.toByte()
        }
        return out
    }
}
