package io.github.katznmeik.cinexperience.core.ticket.pdf

import io.github.katznmeik.cinexperience.core.ticket.TicketPdfError

/**
 * Why the reader stops. The message is for tests and debugging only and never
 * contains text from the file.
 */
internal class TicketPdfException(val error: TicketPdfError, message: String) : Exception(message)

internal fun refuse(error: TicketPdfError, message: String): Nothing = throw TicketPdfException(error, message)

internal fun damaged(message: String): Nothing = refuse(TicketPdfError.DAMAGED, message)

/** The PDF objects the reader understands (ISO 32000-1, 7.3). */
internal sealed interface PdfObject

internal data object PdfNull : PdfObject

internal data class PdfBoolean(val value: Boolean) : PdfObject

/** A number; [isInteger] if it was written without a decimal point. */
internal data class PdfNumber(val value: Double, val isInteger: Boolean) : PdfObject {
    /** The value as an Int if it is an integer in Int range. */
    fun intOrNull(): Int? = if (isInteger && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) value.toInt() else null
}

/** A name without its slash, `#xx` escapes decoded (as Latin-1 characters). */
internal data class PdfName(val name: String) : PdfObject

/** A literal or hexadecimal string, as bytes. */
internal class PdfString(val bytes: ByteArray) : PdfObject

internal class PdfArray(val items: List<PdfObject>) : PdfObject

internal class PdfDictionary(private val entries: Map<String, PdfObject>) : PdfObject {
    operator fun get(key: String): PdfObject? = entries[key]
    fun containsKey(key: String): Boolean = key in entries
}

internal data class PdfReference(val number: Int, val generation: Int) : PdfObject

/**
 * An operator in a content stream or a CMap (`Tj`, `begin…`), or a keyword the
 * object parser met where an object was expected.
 */
internal data class PdfKeyword(val text: String) : PdfObject

/** A stream: its dictionary and where its (still encoded) data lies in the file. */
internal class PdfStream(
    val dictionary: PdfDictionary,
    val objectNumber: Int,
    val dataStart: Int,
    val dataLength: Int,
) : PdfObject

/** A 2D affine transformation `[a b c d e f]` as in PDF (row vectors: `p' = p × M`). */
internal data class Matrix(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    /** This transformation followed by [other]. */
    operator fun times(other: Matrix): Matrix = Matrix(
        a * other.a + b * other.c,
        a * other.b + b * other.d,
        c * other.a + d * other.c,
        c * other.b + d * other.d,
        e * other.a + f * other.c + other.e,
        e * other.b + f * other.d + other.f,
    )

    val isFinite: Boolean get() = a.isFinite() && b.isFinite() && c.isFinite() && d.isFinite() && e.isFinite() && f.isFinite()

    companion object {
        val IDENTITY = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        fun translation(tx: Double, ty: Double) = Matrix(1.0, 0.0, 0.0, 1.0, tx, ty)
    }
}
