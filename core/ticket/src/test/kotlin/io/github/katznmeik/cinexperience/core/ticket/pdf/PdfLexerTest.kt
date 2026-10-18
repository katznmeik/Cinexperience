package io.github.katznmeik.cinexperience.core.ticket.pdf

import io.github.katznmeik.cinexperience.core.ticket.TicketPdfError
import io.github.katznmeik.cinexperience.core.ticket.TicketPdfLimits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLexerTest {

    private fun tokens(text: String): List<PdfToken> {
        val lexer = PdfLexer(text.toByteArray(Charsets.ISO_8859_1), 0)
        return generateSequence { lexer.next() }.toList()
    }

    private fun objects(
        text: String,
        references: Boolean = true,
        budget: ParseBudget = ParseBudget(),
        maxValues: Int = Int.MAX_VALUE,
    ): List<PdfObject> {
        val parser = PdfObjectParser(PdfLexer(text.toByteArray(Charsets.ISO_8859_1), 0), references, budget, maxValues)
        return generateSequence { parser.next() }.toList()
    }

    private fun error(block: () -> Unit): TicketPdfError? = try {
        block()
        null
    } catch (e: TicketPdfException) {
        e.error
    }

    @Test
    fun readsNumbersNamesAndKeywords() {
        assertEquals(
            listOf(
                PdfToken.Number(12.0, true), PdfToken.Number(-3.5, false), PdfToken.Number(0.5, false), PdfToken.Number(4.0, false),
                PdfToken.Number(6.671875, false), PdfToken.Name("Type0"), PdfToken.Name("A B"), PdfToken.Keyword("T*"),
                PdfToken.Keyword("'"), PdfToken.Keyword("\""), PdfToken.Keyword("1.2.3"),
            ),
            tokens("12 -3.5 .5 4. 6.67187500 /Type0 /A#20B T* ' \" 1.2.3"),
        )
    }

    @Test
    fun skipsCommentsAndAllWhitespace() {
        assertEquals(listOf(PdfToken.Keyword("BT"), PdfToken.Keyword("ET")), tokens("% comment\n\u0000\t\u000c BT\r\n%x\rET"))
    }

    @Test
    fun readsLiteralStringsWithEscapesAndNestedParentheses() {
        val text = tokens("(a(b)c\\)\\n\\101\\7\\\nd)").single() as PdfToken.Text
        assertArrayEquals("a(b)c)\nA\u0007d".toByteArray(Charsets.ISO_8859_1), text.bytes)
    }

    @Test
    fun anUnescapedEndOfLineInAStringIsOneLineFeed() {
        val text = tokens("(a\rb\r\nc\nd\\\r\ne)").single() as PdfToken.Text

        assertArrayEquals("a\nb\nc\nde".toByteArray(Charsets.ISO_8859_1), text.bytes)
    }

    @Test
    fun readsHexStringsAndPadsAnOddDigit() {
        assertArrayEquals(byteArrayOf(0x00, 0x2A, 0x7F, 0x50), (tokens("<00 2a7F 5>").single() as PdfToken.Text).bytes)
    }

    @Test
    fun readsArraysDictionariesAndReferences() {
        val dictionary = objects("<< /Kids [3 0 R 4 0 R] /Count 2 /Length 15 0 R /Flag true /N null >>").single() as PdfDictionary
        val kids = dictionary["Kids"] as PdfArray
        assertEquals(listOf(PdfReference(3, 0), PdfReference(4, 0)), kids.items)
        assertEquals(PdfNumber(2.0, true), dictionary["Count"])
        assertEquals(PdfReference(15, 0), dictionary["Length"])
        assertEquals(PdfBoolean(true), dictionary["Flag"])
        assertEquals(PdfNull, dictionary["N"])
    }

    @Test
    fun numbersBeforeAnOperatorAreNoReference() {
        assertEquals(
            listOf(PdfNumber(56.0, true), PdfNumber(-379.0, true), PdfKeyword("Td"), PdfNumber(1.0, true), PdfNumber(0.0, true), PdfKeyword("obj")),
            objects("56 -379 Td 1 0 obj"),
        )
    }

    @Test
    fun contentStreamsHaveNoReferences() {
        assertEquals(listOf(PdfNumber(3.0, true), PdfNumber(0.0, true), PdfKeyword("R")), objects("3 0 R", references = false))
    }

    @Test
    fun brokenSyntaxIsDamaged() {
        for (text in listOf("(unterminated", "<0A1", "<0G>", "<< /A 1", "[1 2", "/A#4", ">", ")", "<< 1 2 >>", "<< /A >>", "<< /A 1 /A 2 >>")) {
            assertEquals(text, TicketPdfError.DAMAGED, error { objects(text) })
        }
    }

    @Test
    fun overlongTokensAreDamaged() {
        assertEquals(TicketPdfError.DAMAGED, error { tokens("x".repeat(300)) })
        assertEquals(TicketPdfError.DAMAGED, error { tokens("/" + "n".repeat(128)) })
    }

    @Test
    fun nestingDeeperThanTheLimitIsRefused() {
        assertEquals(TicketPdfError.NESTING_TOO_DEEP, error { objects("[".repeat(33) + "]".repeat(33)) })
        assertEquals(TicketPdfError.NESTING_TOO_DEEP, error { objects("<< /A ".repeat(33) + ">>".repeat(33)) })
        assertTrue(objects("[".repeat(32) + "]".repeat(32)).single() is PdfArray)
    }

    @Test
    fun tooManyEntriesAreUnsupported() {
        assertEquals(TicketPdfError.UNSUPPORTED, error { objects("[" + "0 ".repeat(10_001) + "]") })
    }

    @Test
    fun everyValueAndKeyCountsAgainstTheDocumentsBudget() {
        // An array of 3 numbers, a dictionary with 2 keys and 2 values, a reference, a keyword: 11 values.
        val budget = ParseBudget()
        repeat(TicketPdfLimits.MAX_PARSED_VALUES - 11) { budget.take() }
        objects("[1 2 3] << /A 1 /B /C >> 4 0 R Tj", budget = budget)

        assertEquals(TicketPdfError.TOO_MANY_OBJECTS, error { objects("0", budget = budget) })
    }

    @Test
    fun oneObjectHoldsAtMostItsLimitOfValues() {
        // The array itself and 4,095 numbers: 4,096 values; each top-level object starts afresh.
        val limit = TicketPdfLimits.MAX_OPERAND_VALUES
        val full = "[" + "0 ".repeat(limit - 1) + "]"
        assertEquals(3, objects("$full $full Tj", references = false, maxValues = limit).size)

        assertEquals(TicketPdfError.UNSUPPORTED, error { objects("[" + "0 ".repeat(limit) + "]", references = false, maxValues = limit) })
        assertEquals(TicketPdfError.UNSUPPORTED, error { objects("[" + "[0] ".repeat(limit / 2) + "]", references = false, maxValues = limit) })
        val keys = (0..limit / 2).joinToString(" ", "<< ", " >>") { "/K$it 0" }
        assertEquals(TicketPdfError.UNSUPPORTED, error { objects(keys, references = false, maxValues = limit) })
    }

    @Test
    fun skipsInlineImageData() {
        // The first "EI" follows a data byte, not whitespace: still image data.
        val lexer = PdfLexer("BI /W 2 /H 2 ID \u00ffEI(x\nEI Q".toByteArray(Charsets.ISO_8859_1), 0)
        repeat(5) { lexer.next() }
        assertEquals(PdfToken.Keyword("ID"), lexer.next())
        lexer.skipInlineImageData()
        assertEquals(PdfToken.Keyword("Q"), lexer.next())
        assertNull(lexer.next())
    }
}
