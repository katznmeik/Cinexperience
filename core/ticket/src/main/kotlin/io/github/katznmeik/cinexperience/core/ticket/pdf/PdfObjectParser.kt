package io.github.katznmeik.cinexperience.core.ticket.pdf

import io.github.katznmeik.cinexperience.core.ticket.TicketPdfError
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfLimits

/**
 * Builds objects from [lexer]'s tokens: numbers, names, strings, arrays, dictionaries,
 * `true`/`false`/`null` and, with [allowReferences], `n g R` references. Arrays and
 * dictionaries may be nested at most [TicketPdfLimits.MAX_NESTING] deep and hold at
 * most [TicketPdfLimits.MAX_CONTAINER_ITEMS] entries each.
 *
 * Any other keyword is returned as a [PdfKeyword] at the top level (content stream
 * operators, `obj`, `stream`…) and is [TicketPdfError.DAMAGED] inside an array or
 * dictionary, except in content streams ([allowReferences] false), whose arrays only
 * hold operands anyway.
 */
internal class PdfObjectParser(private val lexer: PdfLexer, private val allowReferences: Boolean) {
    private val pending = ArrayDeque<PdfToken>()

    /** The next object or keyword, or `null` at the end. */
    fun next(): PdfObject? {
        val token = nextToken() ?: return null
        return parse(token, depth = 0)
    }

    /** Hands the lexer back (only when nothing is pending), e.g. to skip a stream's data. */
    fun lexerWithoutPending(): PdfLexer {
        check(pending.isEmpty()) { "Tokens are pending" }
        return lexer
    }

    private fun nextToken(): PdfToken? = pending.removeFirstOrNull() ?: lexer.next()

    private fun parse(token: PdfToken, depth: Int): PdfObject = when (token) {
        is PdfToken.Number -> if (allowReferences && token.isInteger && token.value >= 0) referenceOrNumber(token) else PdfNumber(token.value, token.isInteger)
        is PdfToken.Name -> PdfName(token.name)
        is PdfToken.Text -> PdfString(token.bytes)
        is PdfToken.Keyword -> when (token.text) {
            "true" -> PdfBoolean(true)
            "false" -> PdfBoolean(false)
            "null" -> PdfNull
            else -> PdfKeyword(token.text)
        }
        PdfToken.ArrayStart -> array(depth + 1)
        PdfToken.DictStart -> dictionary(depth + 1)
        PdfToken.ArrayEnd, PdfToken.DictEnd -> damaged("Unexpected end of an array or dictionary")
    }

    /** `n g R` is a reference; otherwise the two tokens read ahead are kept for later. */
    private fun referenceOrNumber(first: PdfToken.Number): PdfObject {
        val second = nextToken()
        if (second is PdfToken.Number && second.isInteger && second.value >= 0) {
            val third = nextToken()
            if (third == R) {
                val number = first.value
                val generation = second.value
                if (number > Int.MAX_VALUE || generation > MAX_GENERATION) damaged("Reference out of range")
                return PdfReference(number.toInt(), generation.toInt())
            }
            if (third != null) pending.addFirst(third)
        }
        if (second != null) pending.addFirst(second)
        return PdfNumber(first.value, isInteger = true)
    }

    private fun array(depth: Int): PdfArray {
        checkDepth(depth)
        val items = ArrayList<PdfObject>()
        while (true) {
            val token = nextToken() ?: damaged("Unterminated array")
            if (token == PdfToken.ArrayEnd) return PdfArray(items)
            val item = parse(token, depth)
            if (item is PdfKeyword && allowReferences) damaged("Keyword inside an array")
            items += item
            if (items.size > TicketPdfLimits.MAX_CONTAINER_ITEMS) refuse(TicketPdfError.UNSUPPORTED, "Array too long")
        }
    }

    private fun dictionary(depth: Int): PdfDictionary {
        checkDepth(depth)
        val entries = LinkedHashMap<String, PdfObject>()
        while (true) {
            val token = nextToken() ?: damaged("Unterminated dictionary")
            if (token == PdfToken.DictEnd) return PdfDictionary(entries)
            val key = (token as? PdfToken.Name)?.name ?: damaged("Dictionary key is not a name")
            val valueToken = nextToken() ?: damaged("Unterminated dictionary")
            if (valueToken == PdfToken.DictEnd) damaged("Dictionary key without a value")
            val value = parse(valueToken, depth)
            if (value is PdfKeyword) damaged("Keyword as a dictionary value")
            if (entries.put(key, value) != null) damaged("Duplicate dictionary key")
            if (entries.size > TicketPdfLimits.MAX_CONTAINER_ITEMS) refuse(TicketPdfError.UNSUPPORTED, "Dictionary too long")
        }
    }

    private fun checkDepth(depth: Int) {
        if (depth > TicketPdfLimits.MAX_NESTING) refuse(TicketPdfError.NESTING_TOO_DEEP, "Arrays or dictionaries nested too deep")
    }

    private companion object {
        val R = PdfToken.Keyword("R")
        const val MAX_GENERATION = 65_535.0
    }
}
