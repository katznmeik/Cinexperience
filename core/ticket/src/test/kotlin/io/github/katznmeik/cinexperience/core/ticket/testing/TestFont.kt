package io.github.katznmeik.cinexperience.core.ticket.testing

import java.util.Locale

/** How a [TestFont] writes its `/ToUnicode` CMap. */
internal enum class CMapStyle {
    /** Like Qt 5.5: `<0000> <0000> <0000>` and one `bfrange` with an array of targets. */
    QT,

    /** `bfrange` with one target per run of consecutive codes and characters (the counting form). */
    RANGES,

    /** One `bfchar` per glyph. */
    BFCHAR,
}

/**
 * An invented Type 0 font with Identity-H codes and a `/ToUnicode` CMap, without a
 * font program (the reader never needs one). Glyph codes are numbered from 3 in the
 * order of [ALPHABET], so they never equal the characters' code points, and the space
 * maps to a tab, as in Qt's tables.
 */
internal class TestFont(
    val resourceName: String,
    val baseFont: String,
    private val bold: Boolean = baseFont.contains("Bold"),
    private val style: CMapStyle = CMapStyle.QT,
    private val spaceTarget: Char = '\t',
) {
    fun code(char: Char): Int {
        val index = ALPHABET.indexOf(char)
        require(index >= 0) { "No glyph for U+%04X".format(Locale.ROOT, char.code) }
        return FIRST_CODE + index
    }

    /** The glyph's advance in 1/1000 of the font size. */
    fun width(char: Char): Int = when {
        char == ' ' -> 278
        char in ".,:;'!|" -> 278
        char.isUpperCase() -> if (bold) 722 else 667
        char.isDigit() -> 556
        else -> if (bold) 611 else 556
    }

    fun hex(char: Char): String = "<%04X>".format(Locale.ROOT, code(char))

    /** The CMap text. */
    fun toUnicode(): String = buildString {
        append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
        append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        val targets = ALPHABET.map { if (it == ' ') spaceTarget else it }
        when (style) {
            CMapStyle.QT -> {
                append("2 beginbfrange\n<0000> <0000> <0000>\n")
                append("<%04X> <%04X> [".format(Locale.ROOT, FIRST_CODE, FIRST_CODE + ALPHABET.length - 1))
                append(targets.joinToString(" ") { "<%04X>".format(Locale.ROOT, it.code) })
                append("]\nendbfrange\n")
            }
            CMapStyle.RANGES -> {
                val runs = ArrayList<IntRange>()
                var start = 0
                for (i in 1..targets.size) {
                    if (i == targets.size || targets[i].code != targets[i - 1].code + 1) {
                        runs += start until i
                        start = i
                    }
                }
                append("${runs.size} beginbfrange\n")
                runs.forEach { run ->
                    append("<%04X> <%04X> <%04X>\n".format(Locale.ROOT, FIRST_CODE + run.first, FIRST_CODE + run.last, targets[run.first].code))
                }
                append("endbfrange\n")
            }
            CMapStyle.BFCHAR -> {
                append("${targets.size} beginbfchar\n")
                targets.forEachIndexed { i, target -> append("<%04X> <%04X>\n".format(Locale.ROOT, FIRST_CODE + i, target.code)) }
                append("endbfchar\n")
            }
        }
        append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
    }

    /** Adds the font's objects; returns the Type 0 font's object number. */
    fun addTo(builder: PdfBuilder, toUnicode: String = toUnicode()): Int {
        val descriptor = builder.add(
            "<< /Type /FontDescriptor /FontName /QXAAAA+$baseFont /Flags 4 /FontBBox [-665 -325 2028 1037] " +
                "/ItalicAngle 0 /Ascent 905 /Descent -212 /CapHeight 716 /StemV 80 >>",
        )
        val widths = ALPHABET.map(::width).joinToString(" ")
        val cid = builder.add(
            "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /$baseFont " +
                "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> " +
                "/FontDescriptor $descriptor 0 R /CIDToGIDMap /Identity /W [0 [750] $FIRST_CODE [$widths]] >>",
        )
        val cmap = builder.addStream("", toUnicode.toByteArray(Charsets.ISO_8859_1), directLength = true)
        return builder.add(
            "<< /Type /Font /Subtype /Type0 /BaseFont /$baseFont /Encoding /Identity-H " +
                "/DescendantFonts [$cid 0 R] /ToUnicode $cmap 0 R>>",
        )
    }

    companion object {
        const val FIRST_CODE = 3

        /** Every character the invented tickets use, in code point order (one CMap range). */
        val ALPHABET: String = (
            (' '..'~').joinToString("") + "\u00c4\u00d6\u00dc\u00df\u00e1\u00e4\u00e9\u00f6\u00fc\u2013\u2019\u20ac"
            ).toSortedSet().joinToString("")
    }
}

/**
 * A page's content stream as Qt 5.5 writes it: the page flipped once
 * (`1 0 0 -1 0 1191 cm`), then every text item in its own `q … BT … ET Q` with a
 * flipped text matrix and one `Td` + `Tj` per glyph, positions counted from the top.
 */
internal class QtContent(private val pageHeight: Int = 1191) {
    private val out = StringBuilder("/GSa gs /CSp cs /CSp CS\n1 0 0 -1 0 $pageHeight cm\nq q\nQ\nQ q\n")

    /** One text item; [top] is its baseline in points from the top of the page. Returns its end. */
    fun text(font: TestFont, size: Double, x: Double, top: Double, text: String): Double {
        out.append("q\n/CSp cs 0 0 0 scn\n/GSa gs\nBT\n/${font.resourceName} ${num(size)} Tf 1 0 0 -1 0 0 Tm\n")
        var previous: Char? = null
        for (char in text) {
            if (previous == null) {
                out.append("${num(x)} ${num(-top)} Td ")
            } else {
                out.append("${num(font.width(previous) / 1000.0 * size)} 0 Td ")
            }
            out.append("${font.hex(char)} Tj\n")
            previous = char
        }
        out.append("ET\nQ\n0 0 0 SCN\n0 w 2 J 2 j [] 0 d\n")
        return x + text.sumOf { font.width(it) } / 1000.0 * size
    }

    /** An image XObject [side] points square with its top left corner at ([x], [top]). */
    fun image(name: String, x: Double, top: Double, side: Double, pixels: Int) {
        out.append("q\n/GSa gs\n${num(side / pixels)} 0 0 ${num(side / pixels)} ${num(x)} ${num(top)} cm\n")
        out.append("/CSp cs 0 0 0 scn\n/GSa gs\n$pixels 0 0 -$pixels 0 $pixels cm /$name  Do\nQ\n")
    }

    fun raw(text: String) {
        out.append(text).append('\n')
    }

    override fun toString(): String = out.toString() + "Q\n"

    companion object {
        fun num(value: Double): String =
            if (value == Math.rint(value)) value.toLong().toString() else String.format(Locale.ROOT, "%.8f", value)
    }
}
