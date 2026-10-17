package io.github.katznmeik.cinexperience.core.ticket.text

import io.github.katznmeik.cinexperience.core.ticket.TicketPdfError
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfArray
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfDictionary
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfDocument
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfNumber
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfObject
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfReference
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfStream
import io.github.katznmeik.cinexperience.core.ticket.pdf.TicketPdfException
import java.util.IdentityHashMap

/** A font the reader can map to text: Type 0, Identity-H, with a `/ToUnicode` CMap. */
internal class PdfFont(val toUnicode: Map<Int, String>, val widths: GlyphWidths, val isBold: Boolean)

/** Glyph widths of a CID font (`/W`, `/DW`, in 1/1000 text space units). */
internal class GlyphWidths(
    private val explicit: Map<Int, Double>,
    private val ranges: List<WidthRange>,
    private val default: Double,
) {
    class WidthRange(val first: Int, val last: Int, val width: Double)

    fun width(code: Int): Double = explicit[code] ?: ranges.firstOrNull { code in it.first..it.last }?.width ?: default

    companion object {
        private const val DEFAULT_WIDTH = 1000.0
        private val SENSIBLE = 0.0..10_000.0

        /** Reads `/W` and `/DW` of [cidFont]; malformed entries are skipped. */
        fun of(document: PdfDocument, cidFont: PdfDictionary?): GlyphWidths {
            val default = document.number(cidFont?.get("DW"))?.takeIf { it in SENSIBLE } ?: DEFAULT_WIDTH
            val items = (document.resolve(cidFont?.get("W")) as? PdfArray)?.items.orEmpty().map(document::resolve)
            val explicit = HashMap<Int, Double>()
            val ranges = ArrayList<WidthRange>()
            var i = 0
            while (i < items.size) {
                val first = (items[i] as? PdfNumber)?.intOrNull()
                val next = items.getOrNull(i + 1)
                when {
                    first == null || first !in 0..0xFFFF -> i++
                    next is PdfArray -> {
                        next.items.forEachIndexed { index, item ->
                            val width = document.number(item)
                            if (width != null && width in SENSIBLE && first + index <= 0xFFFF) explicit[first + index] = width
                        }
                        i += 2
                    }
                    else -> {
                        val last = (next as? PdfNumber)?.intOrNull()
                        val width = (items.getOrNull(i + 2) as? PdfNumber)?.value
                        if (last != null && last in first..0xFFFF && width != null && width in SENSIBLE) ranges += WidthRange(first, last, width)
                        i += 3
                    }
                }
            }
            return GlyphWidths(explicit, ranges, default)
        }
    }
}

/**
 * Loads the fonts of a document once each. A font the reader can't map (another
 * type or encoding, no or an unusable `/ToUnicode`) is `null`: its text is dropped,
 * never guessed. Resource limits and damage found while loading still refuse the file.
 */
internal class FontCache(private val document: PdfDocument) {
    // Each font and each CMap is built once, however often a content stream selects it:
    // by object number, or by identity for a font given directly in the resources.
    private val byObject = HashMap<Int, PdfFont?>()
    private val byDictionary = IdentityHashMap<PdfDictionary, PdfFont?>()
    private val cmaps = HashMap<Int, Map<Int, String>?>()

    fun load(font: PdfObject?): PdfFont? {
        if (font is PdfReference) {
            if (font.number in byObject) return byObject[font.number]
            return create(font).also { byObject[font.number] = it }
        }
        val dictionary = font as? PdfDictionary ?: return null
        if (dictionary in byDictionary) return byDictionary[dictionary]
        return create(dictionary).also { byDictionary[dictionary] = it }
    }

    private fun create(font: PdfObject?): PdfFont? {
        val dictionary = document.dictionary(font) ?: return null
        if (document.name(dictionary["Subtype"]) != "Type0") return null
        if (document.name(dictionary["Encoding"]) != "Identity-H") return null
        val toUnicode = document.resolve(dictionary["ToUnicode"]) as? PdfStream ?: return null
        val map = if (toUnicode.objectNumber in cmaps) cmaps[toUnicode.objectNumber] else cmap(toUnicode).also { cmaps[toUnicode.objectNumber] = it }
        if (map == null) return null
        val descendant = (document.resolve(dictionary["DescendantFonts"]) as? PdfArray)?.items?.singleOrNull()
        val widths = GlyphWidths.of(document, document.dictionary(descendant))
        val baseFont = document.name(dictionary["BaseFont"]).orEmpty().substringAfter('+')
        return PdfFont(map, widths, isBold = baseFont.contains("Bold", ignoreCase = true))
    }

    private fun cmap(stream: PdfStream): Map<Int, String>? {
        val data = try {
            document.decodedData(stream)
        } catch (e: TicketPdfException) {
            if (e.error == TicketPdfError.UNSUPPORTED) return null else throw e
        }
        return try {
            ToUnicodeCMap.parse(data)
        } catch (e: TicketPdfException) {
            // A CMap with broken syntax: this font's text is dropped; limits still refuse the file.
            if (e.error == TicketPdfError.DAMAGED) null else throw e
        }
    }
}
