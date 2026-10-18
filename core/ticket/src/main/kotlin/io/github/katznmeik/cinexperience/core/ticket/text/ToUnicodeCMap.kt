package io.github.katznmeik.cinexperience.core.ticket.text

import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfArray
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfKeyword
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfLexer
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfObject
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfObjectParser
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfString

/**
 * A font's `/ToUnicode` CMap for two-byte codes (Identity-H, ISO 32000-1, 9.10.3):
 * `bfchar` pairs and both forms of `bfrange` (`<lo> <hi> <dst>`, where the last
 * UTF-16 unit of `dst` counts up, and `<lo> <hi> [<dst1> <dst2> …]`). Everything
 * else in the CMap is skipped.
 *
 * Entries the reader can't use are skipped: codes that aren't two bytes, targets that
 * aren't 1 to [MAX_TARGET_UNITS] UTF-16 units, ranges with `hi < lo` or more than
 * [MAX_RANGE] codes. The targets are untrusted text; the ticket fields are cleaned
 * later ([io.github.katznmeik.cinexperience.core.ticket.TicketText]).
 */
internal object ToUnicodeCMap {
    /** Far more than a subset font has (Yorck's have about 70 glyphs each). */
    const val MAX_ENTRIES: Int = 4_096

    /** Codes processed (each `bfchar` pair, each code of a range, repeated or not) before the CMap is given up. */
    const val MAX_PROCESSED: Int = 4 * MAX_ENTRIES
    const val MAX_RANGE: Int = 256
    const val MAX_TARGET_UNITS: Int = 8

    /**
     * The code → text table, or `null` if the CMap holds more than [MAX_ENTRIES]
     * mappings or makes the reader process more than [MAX_PROCESSED] codes (ranges
     * repeated over the same codes); then the font's text is dropped. Syntax errors
     * are thrown as DAMAGED.
     */
    fun parse(data: ByteArray): Map<Int, String>? {
        val parser = PdfObjectParser(PdfLexer(data, 0), allowReferences = false)
        val map = HashMap<Int, String>()
        var processed = 0
        while (true) {
            when ((parser.next() ?: break) as? PdfKeyword) {
                BEGIN_BFCHAR -> while (true) {
                    val source = parser.next() ?: return map
                    if (source == END_BFCHAR) break
                    val target = parser.next() ?: return map
                    if (++processed > MAX_PROCESSED) return null
                    val code = code(source) ?: continue
                    val text = text(target) ?: continue
                    map[code] = text
                    if (map.size > MAX_ENTRIES) return null
                }
                BEGIN_BFRANGE -> while (true) {
                    val low = parser.next() ?: return map
                    if (low == END_BFRANGE) break
                    val high = parser.next() ?: return map
                    val target = parser.next() ?: return map
                    processed += rangeSize(code(low), code(high))
                    if (processed > MAX_PROCESSED) return null
                    if (!addRange(map, code(low), code(high), target)) continue
                    if (map.size > MAX_ENTRIES) return null
                }
                else -> Unit
            }
        }
        return map
    }

    /** The codes a range makes the reader process: its size if it is used, else 1. */
    private fun rangeSize(low: Int?, high: Int?): Int =
        if (low == null || high == null || high < low || high - low >= MAX_RANGE) 1 else high - low + 1

    private fun addRange(map: MutableMap<Int, String>, low: Int?, high: Int?, target: PdfObject): Boolean {
        if (low == null || high == null || high < low || high - low >= MAX_RANGE) return false
        when (target) {
            is PdfArray -> target.items.take(high - low + 1).forEachIndexed { i, item ->
                text(item)?.let { map[low + i] = it }
            }
            is PdfString -> {
                val units = units(target) ?: return false
                for (i in 0..(high - low)) {
                    val last = units.last() + i
                    if (last > 0xFFFF) break
                    val chars = CharArray(units.size) { index -> (if (index == units.lastIndex) last else units[index]).toChar() }
                    map[low + i] = String(chars)
                }
            }
            else -> return false
        }
        return true
    }

    private fun code(obj: PdfObject): Int? {
        val bytes = (obj as? PdfString)?.bytes ?: return null
        if (bytes.size != 2) return null
        return ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
    }

    private fun text(obj: PdfObject): String? = units(obj)?.let { units -> String(CharArray(units.size) { units[it].toChar() }) }

    /** UTF-16BE units of a target string. */
    private fun units(obj: PdfObject): IntArray? {
        val bytes = (obj as? PdfString)?.bytes ?: return null
        if (bytes.isEmpty() || bytes.size % 2 != 0 || bytes.size / 2 > MAX_TARGET_UNITS) return null
        return IntArray(bytes.size / 2) { ((bytes[2 * it].toInt() and 0xFF) shl 8) or (bytes[2 * it + 1].toInt() and 0xFF) }
    }

    private val BEGIN_BFCHAR = PdfKeyword("beginbfchar")
    private val END_BFCHAR = PdfKeyword("endbfchar")
    private val BEGIN_BFRANGE = PdfKeyword("beginbfrange")
    private val END_BFRANGE = PdfKeyword("endbfrange")
}
