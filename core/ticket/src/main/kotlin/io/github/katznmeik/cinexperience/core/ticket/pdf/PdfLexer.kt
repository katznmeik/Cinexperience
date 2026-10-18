package io.github.katznmeik.cinexperience.core.ticket.pdf

import java.io.ByteArrayOutputStream

/** A token of PDF syntax (ISO 32000-1, 7.2 and 7.3). */
internal sealed interface PdfToken {
    data class Number(val value: Double, val isInteger: Boolean) : PdfToken
    data class Name(val name: String) : PdfToken
    class Text(val bytes: ByteArray) : PdfToken
    data class Keyword(val text: String) : PdfToken
    data object ArrayStart : PdfToken
    data object ArrayEnd : PdfToken
    data object DictStart : PdfToken
    data object DictEnd : PdfToken
}

/**
 * Splits `data[start until end]` into tokens. It never reads outside that range and
 * always moves forward, so it ends on any input; malformed syntax is
 * [io.github.katznmeik.cinexperience.core.ticket.TicketPdfError.DAMAGED].
 */
internal class PdfLexer(private val data: ByteArray, start: Int, val end: Int = data.size) {
    init {
        require(start in 0..end && end <= data.size) { "Range outside the data" }
    }

    var position: Int = start
        private set

    /** The next token, or `null` at the end of the range. */
    fun next(): PdfToken? {
        skipWhitespaceAndComments()
        if (position >= end) return null
        return when (val c = byteAt(position)) {
            '['.code -> { position++; PdfToken.ArrayStart }
            ']'.code -> { position++; PdfToken.ArrayEnd }
            '('.code -> literalString()
            '<'.code -> if (position + 1 < end && byteAt(position + 1) == '<'.code) {
                position += 2
                PdfToken.DictStart
            } else {
                hexString()
            }
            '>'.code -> if (position + 1 < end && byteAt(position + 1) == '>'.code) {
                position += 2
                PdfToken.DictEnd
            } else {
                damaged("Unexpected '>'")
            }
            '/'.code -> name()
            '{'.code, '}'.code -> { position++; PdfToken.Keyword(c.toChar().toString()) }
            ')'.code -> damaged("Unexpected ')'")
            else -> regular()
        }
    }

    /**
     * After the keyword `stream`: skips its end of line (CR LF or LF, also a lone CR)
     * and returns where the data starts.
     */
    fun streamDataStart(): Int {
        if (position < end && byteAt(position) == CR) position++
        if (position < end && byteAt(position) == LF) position++
        return position
    }

    /** Moves to [newPosition] (inside the range), e.g. past a stream's data. */
    fun seek(newPosition: Int) {
        require(newPosition in position..end) { "Can only move forward inside the range" }
        position = newPosition
    }

    /**
     * After the `ID` operator of an inline image (which the reader never decodes): skips
     * its data up to and including the `EI` that follows whitespace and stands alone.
     */
    fun skipInlineImageData() {
        var i = position + 1 // the single whitespace after ID
        while (i + 1 < end) {
            if (byteAt(i) == 'E'.code && byteAt(i + 1) == 'I'.code && isWhitespace(byteAt(i - 1)) &&
                (i + 2 >= end || isWhitespace(byteAt(i + 2)) || isDelimiter(byteAt(i + 2)))
            ) {
                position = i + 2
                return
            }
            i++
        }
        position = end
    }

    private fun byteAt(index: Int): Int = data[index].toInt() and 0xFF

    private fun skipWhitespaceAndComments() {
        while (position < end) {
            val c = byteAt(position)
            when {
                isWhitespace(c) -> position++
                c == '%'.code -> {
                    while (position < end && byteAt(position) != CR && byteAt(position) != LF) position++
                }
                else -> return
            }
        }
    }

    private fun regular(): PdfToken {
        val start = position
        while (position < end && !isWhitespace(byteAt(position)) && !isDelimiter(byteAt(position))) {
            position++
            if (position - start > MAX_REGULAR_TOKEN) damaged("Token too long")
        }
        val text = String(data, start, position - start, Charsets.ISO_8859_1)
        return number(text) ?: PdfToken.Keyword(text)
    }

    private fun name(): PdfToken {
        position++ // '/'
        val out = ByteArrayOutputStream()
        while (position < end && !isWhitespace(byteAt(position)) && !isDelimiter(byteAt(position))) {
            val c = byteAt(position++)
            if (c == '#'.code) {
                val high = if (position < end) hexValue(byteAt(position)) else -1
                val low = if (position + 1 < end) hexValue(byteAt(position + 1)) else -1
                if (high < 0 || low < 0) damaged("Bad # escape in a name")
                position += 2
                out.write(high * 16 + low)
            } else {
                out.write(c)
            }
            if (out.size() > MAX_NAME_LENGTH) damaged("Name too long")
        }
        return PdfToken.Name(String(out.toByteArray(), Charsets.ISO_8859_1))
    }

    private fun literalString(): PdfToken {
        position++ // '('
        val out = ByteArrayOutputStream()
        var depth = 1
        while (position < end) {
            when (val c = byteAt(position++)) {
                '\\'.code -> {
                    if (position >= end) break
                    when (val e = byteAt(position++)) {
                        'n'.code -> out.write(LF)
                        'r'.code -> out.write(CR)
                        't'.code -> out.write(0x09)
                        'b'.code -> out.write(0x08)
                        'f'.code -> out.write(0x0C)
                        CR -> if (position < end && byteAt(position) == LF) position++ // line continuation
                        LF -> Unit // line continuation
                        in '0'.code..'7'.code -> {
                            var value = e - '0'.code
                            repeat(2) {
                                if (position < end && byteAt(position) in '0'.code..'7'.code) {
                                    value = value * 8 + (byteAt(position++) - '0'.code)
                                }
                            }
                            out.write(value and 0xFF)
                        }
                        else -> out.write(e) // \( \) \\ and unknown escapes: the character itself
                    }
                }
                '('.code -> { depth++; out.write(c) }
                ')'.code -> {
                    depth--
                    if (depth == 0) return PdfToken.Text(out.toByteArray())
                    out.write(c)
                }
                CR -> {
                    // An unescaped end of line (CR, LF or CR LF) is one LF (ISO 32000-1, 7.3.4.2).
                    if (position < end && byteAt(position) == LF) position++
                    out.write(LF)
                }
                else -> out.write(c)
            }
        }
        damaged("Unterminated string")
    }

    private fun hexString(): PdfToken {
        position++ // '<'
        val out = ByteArrayOutputStream()
        var high = -1
        while (position < end) {
            val c = byteAt(position++)
            when {
                c == '>'.code -> {
                    if (high >= 0) out.write(high * 16) // odd number of digits: the last one is followed by 0
                    return PdfToken.Text(out.toByteArray())
                }
                isWhitespace(c) -> Unit
                else -> {
                    val digit = hexValue(c)
                    if (digit < 0) damaged("Bad character in a hex string")
                    if (high < 0) {
                        high = digit
                    } else {
                        out.write(high * 16 + digit)
                        high = -1
                    }
                }
            }
        }
        damaged("Unterminated hex string")
    }

    private fun number(text: String): PdfToken.Number? {
        var i = 0
        if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
        var digits = 0
        var point = false
        while (i < text.length) {
            val ch = text[i]
            when {
                ch in '0'..'9' -> digits++
                ch == '.' && !point -> point = true
                else -> return null
            }
            i++
        }
        if (digits == 0) return null
        val value = text.toDoubleOrNull() ?: return null
        return PdfToken.Number(value, isInteger = !point)
    }

    companion object {
        private const val CR = 0x0D
        private const val LF = 0x0A

        /** Longer keywords or numbers than any real PDF has. */
        private const val MAX_REGULAR_TOKEN = 256

        /** PDF's own limit for names (ISO 32000-1, Annex C). */
        private const val MAX_NAME_LENGTH = 127

        fun isWhitespace(c: Int): Boolean = c == 0x00 || c == 0x09 || c == LF || c == 0x0C || c == CR || c == 0x20

        fun isDelimiter(c: Int): Boolean = when (c) {
            '('.code, ')'.code, '<'.code, '>'.code, '['.code, ']'.code, '{'.code, '}'.code, '/'.code, '%'.code -> true
            else -> false
        }

        private fun hexValue(c: Int): Int = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }
    }
}
