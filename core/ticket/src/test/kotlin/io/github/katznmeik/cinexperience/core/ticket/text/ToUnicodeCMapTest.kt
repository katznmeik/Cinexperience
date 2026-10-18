package io.github.katznmeik.cinexperience.core.ticket.text

import io.github.katznmeik.cinexperience.core.ticket.TicketPdfError
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfLimits
import io.github.katznmeik.cinexperience.core.ticket.pdf.ParseBudget
import io.github.katznmeik.cinexperience.core.ticket.pdf.TicketPdfException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToUnicodeCMapTest {

    private fun parse(body: String, codes: CMapCodeBudget = CMapCodeBudget()): Map<Int, String>? = ToUnicodeCMap.parse(
        ("/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n" +
            "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n" +
            "/CMapName /Adobe-Identity-UCS def /CMapType 2 def\n" +
            "1 begincodespacerange <0000> <FFFF> endcodespacerange\n" +
            body + "\nendcmap CMapName currentdict /CMap defineresource pop end end").toByteArray(Charsets.ISO_8859_1),
        ParseBudget(),
        codes,
    )

    private fun error(block: () -> Unit): TicketPdfError? = try {
        block()
        null
    } catch (e: TicketPdfException) {
        e.error
    }

    @Test
    fun readsBfcharPairs() {
        assertEquals(mapOf(0x0003 to "\t", 0x0024 to "A", 0x0102 to "\u00e4"), parse("3 beginbfchar <0003> <0009> <0024> <0041> <0102> <00E4> endbfchar"))
    }

    @Test
    fun readsBothFormsOfBfrange() {
        val map = parse("2 beginbfrange <0010> <0012> <0061> <0020> <0022> [<0058> <0059> <005A>] endbfrange")

        assertEquals(mapOf(0x10 to "a", 0x11 to "b", 0x12 to "c", 0x20 to "X", 0x21 to "Y", 0x22 to "Z"), map)
    }

    @Test
    fun readsSurrogatePairsAndLigatures() {
        assertEquals(mapOf(1 to "\ud83c\udfac", 2 to "fi"), parse("2 beginbfchar <0001> <D83CDFAC> <0002> <00660069> endbfchar"))
    }

    @Test
    fun aShortArrayMapsOnlyItsEntries() {
        assertEquals(mapOf(5 to "A"), parse("1 beginbfrange <0005> <0009> [<0041>] endbfrange"))
    }

    @Test
    fun skipsEntriesTheReaderCantUse() {
        val map = parse(
            "6 beginbfchar <03> <0041> <000001> <0041> <0004> <41> <0005> <> <0006> /Name <0007> <004100420043004400450046004700480049> endbfchar\n" +
                "3 beginbfrange <0020> <0010> <0041> <0000> <0100> <0041> <0030> <0031> (x) endbfrange\n" +
                "1 beginbfchar <0008> <0042> endbfchar",
        )

        assertEquals(mapOf(8 to "B"), map)
    }

    @Test
    fun aCountingRangeStopsAtTheEndOfTheBmp() {
        assertEquals(mapOf(1 to "\ufffe", 2 to "\uffff"), parse("1 beginbfrange <0001> <0004> <FFFE> endbfrange"))
    }

    @Test
    fun tooManyEntriesDropTheWholeTable() {
        val ranges = (0 until 17).joinToString(" ") { "<%02X00> <%02XFF> <0041>".format(it, it) }

        assertNull(parse("17 beginbfrange $ranges endbfrange"))
    }

    @Test
    fun brokenSyntaxIsDamaged() {
        assertEquals(TicketPdfError.DAMAGED, error { parse("1 beginbfchar <0001> <00G1> endbfchar") })
    }

    @Test
    fun anUnterminatedSectionKeepsWhatWasRead() {
        val data = "beginbfchar <0001> <0041> <0002>".toByteArray(Charsets.ISO_8859_1)

        assertEquals(mapOf(1 to "A"), ToUnicodeCMap.parse(data, ParseBudget(), CMapCodeBudget()))
    }

    @Test
    fun theCodesOfAllCMapsCountTogether() {
        // Each range is used; 64 of them reach the document's limit, the next one is refused.
        val codes = CMapCodeBudget()
        val ranges = (0 until 16).joinToString(" ") { "<%02X00> <%02XFF> <0041>".format(it, it) }
        repeat(TicketPdfLimits.MAX_CMAP_CODES / 4_096) { assertEquals(4_096, parse("16 beginbfrange $ranges endbfrange", codes)?.size) }

        assertEquals(TicketPdfError.UNSUPPORTED, error { parse("1 beginbfchar <0001> <0041> endbfchar", codes) })
    }

    @Test
    fun rangesOverTheSameCodesCountEachTime() {
        val repeated = "<0000> <00FF> <0041> ".repeat(TicketPdfLimits.MAX_CMAP_CODES / 256 + 1)

        assertEquals(TicketPdfError.UNSUPPORTED, error { parse("1 beginbfrange $repeated endbfrange") })
    }

    @Test
    fun aTargetArrayOfMoreValuesThanOneObjectMayHoldIsRefused() {
        val targets = "<0041> ".repeat(TicketPdfLimits.MAX_OPERAND_VALUES)

        assertEquals(TicketPdfError.UNSUPPORTED, error { parse("1 beginbfrange <0000> <00FF> [$targets] endbfrange") })
    }
}
