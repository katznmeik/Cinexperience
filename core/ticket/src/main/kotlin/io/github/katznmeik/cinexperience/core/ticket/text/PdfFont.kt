package io.github.katznmeik.cinexperience.core.ticket.text

import io.github.katznmeik.cinexperience.core.ticket.TicketPdfError
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfLimits
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfArray
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfDictionary
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfDocument
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfNumber
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfObject
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfReference
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfStream
import io.github.katznmeik.cinexperience.core.ticket.pdf.TicketPdfException
import io.github.katznmeik.cinexperience.core.ticket.pdf.refuse
import java.util.IdentityHashMap

/** A font the reader can map to text: Type 0, Identity-H, with a `/ToUnicode` CMap. */
internal class PdfFont(val toUnicode: Map<Int, String>, val widths: GlyphWidths, val isBold: Boolean)

/** Glyph widths of a CID font (`/W`, `/DW`, in 1/1000 text space units), looked up by code. */
internal class GlyphWidths(private val widths: Map<Int, Double>, private val default: Double) {
    fun width(code: Int): Double = widths[code] ?: default
}

/**
 * Reads the `/W` arrays of a document's CID fonts: each array once (fonts may share
 * one), every code it gives a width counting against [TicketPdfLimits.MAX_GLYPH_WIDTHS]
 * for the whole document ([TicketPdfError.UNSUPPORTED] beyond). Ranges are expanded,
 * so a lookup is one hash access; the first width given for a code counts.
 */
internal class GlyphWidthsReader(private val document: PdfDocument) {
    private val byArray = IdentityHashMap<PdfArray, Map<Int, Double>>()
    private var codes = 0

    /** The widths of [cidFont]; malformed entries are skipped. */
    fun of(cidFont: PdfDictionary?): GlyphWidths {
        val default = document.number(cidFont?.get("DW"))?.takeIf { it in SENSIBLE } ?: DEFAULT_WIDTH
        val array = document.resolve(cidFont?.get("W")) as? PdfArray ?: return GlyphWidths(emptyMap(), default)
        return GlyphWidths(byArray.getOrPut(array) { read(array.items) }, default)
    }

    private fun read(items: List<PdfObject>): Map<Int, Double> {
        val widths = HashMap<Int, Double>()
        var i = 0
        while (i < items.size) {
            val first = (document.resolve(items[i]) as? PdfNumber)?.intOrNull()
            val next = items.getOrNull(i + 1)?.let(document::resolve)
            when {
                first == null || first !in 0..MAX_CODE -> i++
                next is PdfArray -> {
                    count(next.items.size)
                    next.items.forEachIndexed { index, item ->
                        val width = document.number(item)
                        if (width != null && width in SENSIBLE && first + index <= MAX_CODE) widths.putIfAbsent(first + index, width)
                    }
                    i += 2
                }
                else -> {
                    val last = (next as? PdfNumber)?.intOrNull()
                    val width = document.number(items.getOrNull(i + 2))
                    if (last != null && last in first..MAX_CODE && width != null && width in SENSIBLE) {
                        count(last - first + 1)
                        for (code in first..last) widths.putIfAbsent(code, width)
                    }
                    i += 3
                }
            }
        }
        return widths
    }

    private fun count(more: Int) {
        codes += more
        if (codes > TicketPdfLimits.MAX_GLYPH_WIDTHS) {
            refuse(TicketPdfError.UNSUPPORTED, "More than ${TicketPdfLimits.MAX_GLYPH_WIDTHS} glyph widths")
        }
    }

    private companion object {
        const val DEFAULT_WIDTH = 1000.0
        const val MAX_CODE = 0xFFFF
        val SENSIBLE = 0.0..10_000.0
    }
}

/**
 * Loads the fonts of a document once each. A font the reader can't map (another
 * type or encoding, no or an unusable `/ToUnicode`) is `null`: its text is dropped,
 * never guessed. Resource limits and damage found while loading still refuse the file:
 * at most [TicketPdfLimits.MAX_FONTS] fonts are loaded, mapped or not, and all CMaps
 * together process at most [TicketPdfLimits.MAX_CMAP_CODES] codes.
 */
internal class FontCache(private val document: PdfDocument) {
    // Each font and each CMap is built once, however often a content stream selects it:
    // by object number, or by identity for a font given directly in the resources.
    private val byObject = HashMap<Int, PdfFont?>()
    private val byDictionary = IdentityHashMap<PdfDictionary, PdfFont?>()
    private val cmaps = HashMap<Int, Map<Int, String>?>()
    private val widths = GlyphWidthsReader(document)
    private val cmapCodes = CMapCodeBudget()
    private var loaded = 0

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
        if (++loaded > TicketPdfLimits.MAX_FONTS) refuse(TicketPdfError.UNSUPPORTED, "More than ${TicketPdfLimits.MAX_FONTS} fonts")
        val dictionary = document.dictionary(font) ?: return null
        if (document.name(dictionary["Subtype"]) != "Type0") return null
        if (document.name(dictionary["Encoding"]) != "Identity-H") return null
        val toUnicode = document.resolve(dictionary["ToUnicode"]) as? PdfStream ?: return null
        val map = if (toUnicode.objectNumber in cmaps) cmaps[toUnicode.objectNumber] else cmap(toUnicode).also { cmaps[toUnicode.objectNumber] = it }
        if (map == null) return null
        val descendant = (document.resolve(dictionary["DescendantFonts"]) as? PdfArray)?.items?.singleOrNull()
        val baseFont = document.name(dictionary["BaseFont"]).orEmpty().substringAfter('+')
        return PdfFont(map, widths.of(document.dictionary(descendant)), isBold = baseFont.contains("Bold", ignoreCase = true))
    }

    private fun cmap(stream: PdfStream): Map<Int, String>? {
        val data = try {
            document.decodedData(stream)
        } catch (e: TicketPdfException) {
            if (e.error == TicketPdfError.UNSUPPORTED) return null else throw e
        }
        return try {
            ToUnicodeCMap.parse(data, document.parseBudget, cmapCodes)
        } catch (e: TicketPdfException) {
            // A CMap with broken syntax: this font's text is dropped; limits still refuse the file.
            if (e.error == TicketPdfError.DAMAGED) null else throw e
        }
    }
}
