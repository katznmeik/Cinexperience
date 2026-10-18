package io.github.katznmeik.cinexperience.core.ticket.text

import io.github.katznmeik.cinexperience.core.ticket.TicketPdfError
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfLimits
import io.github.katznmeik.cinexperience.core.ticket.pdf.Matrix
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfArray
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfDictionary
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfDocument
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfKeyword
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfLexer
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfName
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfNumber
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfObject
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfObjectParser
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfStream
import io.github.katznmeik.cinexperience.core.ticket.pdf.PdfString
import io.github.katznmeik.cinexperience.core.ticket.pdf.refuse
import kotlin.math.abs

/**
 * One drawn glyph in default user space (y up): its origin on the baseline, its
 * font size and advance there, and the text its font maps it to.
 */
internal class Glyph(val text: String, val x: Double, val y: Double, val size: Double, val advance: Double, val isBold: Boolean)

/** The text and the image XObjects (each once) a page draws. */
internal class PageContent(val glyphs: List<Glyph>, val images: List<PdfStream>)

/** Counts the character codes shown on all pages, mapped to text or not ([TicketPdfLimits.MAX_GLYPHS]). */
internal class GlyphBudget {
    private var count = 0

    fun take() {
        if (++count > TicketPdfLimits.MAX_GLYPHS) refuse(TicketPdfError.TOO_MUCH_TEXT, "More than ${TicketPdfLimits.MAX_GLYPHS} codes shown")
    }
}

/**
 * Runs a page's content stream far enough to place its text and images
 * (ISO 32000-1, 8.4, 9.3, 9.4): `q`/`Q`/`cm`, `BT`/`ET`, `Tf`, `TL`, `Tm`, `Td`, `TD`,
 * `T*`, `Tj`, `TJ`, `'`, `"` and `Do`. Every other operator is skipped with its
 * operands, also the text state operators `Tc`, `Tw`, `Tz`, `Ts` and `Tr` (Qt places
 * every glyph with its own `Td`, so spacing, scaling and rise don't move the text the
 * reader needs), and the spacing operands of `"`; inline images are skipped. Text is
 * read only in fonts [FontCache] can map; glyphs that aren't upright and horizontal
 * are dropped. `Do` on a form XObject is not followed. Every value parsed counts
 * against the document's [PdfDocument.parseBudget]; one operand may hold at most
 * [TicketPdfLimits.MAX_OPERAND_VALUES] values.
 */
internal class ContentInterpreter(
    private val document: PdfDocument,
    private val resources: PdfDictionary?,
    private val fonts: FontCache,
    private val budget: GlyphBudget,
) {
    private class GraphicsState(var ctm: Matrix, var font: PdfFont?, var fontSize: Double, var leading: Double) {
        fun copy() = GraphicsState(ctm, font, fontSize, leading)
    }

    /** The text matrix, moved along the baseline in place for every code shown and every `TJ` number. */
    private class TextMatrix {
        private var a = 1.0
        private var b = 0.0
        private var c = 0.0
        private var d = 1.0
        private var e = 0.0
        private var f = 0.0

        fun set(m: Matrix) {
            a = m.a
            b = m.b
            c = m.c
            d = m.d
            e = m.e
            f = m.f
        }

        /** Moves by [tx] text space units along the baseline: `translation(tx, 0) × this`. */
        fun move(tx: Double) {
            e += tx * a
            f += tx * b
        }

        /** `scale(size) × this × ctm`: where a glyph of [size] is drawn. */
        fun rendering(size: Double, ctm: Matrix): Matrix = Matrix(size * a, size * b, size * c, size * d, e, f) * ctm
    }

    private var state = GraphicsState(Matrix.IDENTITY, font = null, fontSize = 0.0, leading = 0.0)
    private val saved = ArrayDeque<GraphicsState>()
    private var inText = false
    private val textMatrix = TextMatrix()
    private var lineMatrix = Matrix.IDENTITY
    private val glyphs = ArrayList<Glyph>()
    private val images = ArrayList<PdfStream>()
    private val drawn = HashSet<Int>()

    fun run(content: ByteArray): PageContent {
        val lexer = PdfLexer(content, 0)
        val parser = PdfObjectParser(lexer, allowReferences = false, document.parseBudget, TicketPdfLimits.MAX_OPERAND_VALUES)
        val operands = ArrayList<PdfObject>()
        while (true) {
            val obj = parser.next() ?: break
            if (obj is PdfKeyword) {
                execute(obj.text, operands)
                if (obj.text == "ID") parser.lexerWithoutPending().skipInlineImageData()
                operands.clear()
            } else {
                if (operands.size >= MAX_OPERANDS) operands.clear() // garbage, not operands of a real operator
                operands += obj
            }
        }
        return PageContent(glyphs, images)
    }

    private fun execute(operator: String, operands: List<PdfObject>) {
        when (operator) {
            "q" -> {
                if (saved.size >= TicketPdfLimits.MAX_GRAPHICS_STATE_DEPTH) refuse(TicketPdfError.NESTING_TOO_DEEP, "Graphics states nested too deep")
                saved.addLast(state.copy())
            }
            "Q" -> saved.removeLastOrNull()?.let { state = it }
            "cm" -> matrix(operands)?.let { state.ctm = it * state.ctm }
            "BT" -> {
                inText = true
                textMatrix.set(Matrix.IDENTITY)
                lineMatrix = Matrix.IDENTITY
            }
            "ET" -> inText = false
            "Tf" -> if (operands.size >= 2) {
                val name = operands[operands.size - 2] as? PdfName
                val size = (operands.last() as? PdfNumber)?.value
                if (name != null && size != null) {
                    state.font = fonts.load(document.dictionary(resources?.get("Font"))?.get(name.name))
                    state.fontSize = size
                }
            }
            "TL" -> numbers(operands, 1)?.let { state.leading = it[0] }
            "Tm" -> if (inText) matrix(operands)?.let {
                textMatrix.set(it)
                lineMatrix = it
            }
            "Td" -> if (inText) numbers(operands, 2)?.let { moveLine(it[0], it[1]) }
            "TD" -> if (inText) numbers(operands, 2)?.let {
                state.leading = -it[1]
                moveLine(it[0], it[1])
            }
            "T*" -> if (inText) moveLine(0.0, -state.leading)
            "Tj" -> if (inText) (operands.lastOrNull() as? PdfString)?.let { show(it.bytes) }
            "'" -> if (inText) {
                moveLine(0.0, -state.leading)
                (operands.lastOrNull() as? PdfString)?.let { show(it.bytes) }
            }
            "\"" -> if (inText) {
                moveLine(0.0, -state.leading)
                (operands.lastOrNull() as? PdfString)?.let { show(it.bytes) }
            }
            "TJ" -> if (inText) (operands.lastOrNull() as? PdfArray)?.items?.forEach { item ->
                when (item) {
                    is PdfString -> show(item.bytes)
                    is PdfNumber -> textMatrix.move(-item.value / 1000.0 * state.fontSize)
                    else -> Unit
                }
            }
            "Do" -> (operands.lastOrNull() as? PdfName)?.let { drawXObject(it.name) }
            else -> Unit
        }
    }

    private fun moveLine(tx: Double, ty: Double) {
        lineMatrix = Matrix.translation(tx, ty) * lineMatrix
        textMatrix.set(lineMatrix)
    }

    /** Two-byte codes (Identity-H); an odd last byte is ignored. Every code counts against the [budget]. */
    private fun show(bytes: ByteArray) {
        val font = state.font ?: return
        val size = state.fontSize
        var i = 0
        while (i + 1 < bytes.size) {
            val code = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            i += 2
            budget.take()
            val advance = font.widths.width(code) / 1000.0 * size
            val text = font.toUnicode[code]
            if (text != null) place(text, advance, font.isBold)
            textMatrix.move(advance)
        }
    }

    private fun place(text: String, advance: Double, bold: Boolean) {
        val size = state.fontSize
        val rendering = textMatrix.rendering(size, state.ctm)
        if (!rendering.isFinite) return
        val height = rendering.d
        val width = rendering.a
        // Only upright, horizontal text: Yorck's tickets have nothing else.
        if (height <= 0.0 || width <= 0.0 || abs(rendering.b) > UPRIGHT * width || abs(rendering.c) > UPRIGHT * height) return
        glyphs += Glyph(text, rendering.e, rendering.f, height, advance / size * width, bold)
    }

    /** Records each image once, however often it is drawn. */
    private fun drawXObject(name: String) {
        val xObject = document.dictionary(resources?.get("XObject"))?.get(name) ?: return
        val stream = document.resolve(xObject) as? PdfStream ?: return
        if (document.name(stream.dictionary["Subtype"]) == "Image" && drawn.add(stream.objectNumber)) images += stream
    }

    private fun numbers(operands: List<PdfObject>, count: Int): DoubleArray? {
        if (operands.size < count) return null
        val values = DoubleArray(count)
        for (i in 0 until count) {
            val value = (operands[operands.size - count + i] as? PdfNumber)?.value ?: return null
            if (!value.isFinite()) return null
            values[i] = value
        }
        return values
    }

    private fun matrix(operands: List<PdfObject>): Matrix? =
        numbers(operands, 6)?.let { Matrix(it[0], it[1], it[2], it[3], it[4], it[5]) }

    private companion object {
        /** More operands than any operator takes (`"` takes 3, `scn` a few). */
        const val MAX_OPERANDS = 64

        /** How far from upright a glyph may be (a shear of 1 %). */
        const val UPRIGHT = 0.01
    }
}
