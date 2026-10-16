package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.json.LayoutTooLargeException
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_COLUMNS
import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_ROWS
import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_SEAT_ID
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.function.ThrowingRunnable

/** Reading a layout refuses one beyond the model's size and seat id limits as "too large". */
class LayoutJsonLimitsTest {

    /** Layout JSON with one row per pattern; [seatIds] per row, or none (ids assigned from 1). */
    private fun json(patterns: List<String>, nextSeatId: Int? = null, seatIds: List<List<Int>>? = null): String {
        val rows = patterns.mapIndexed { i, cells ->
            val ids = seatIds?.get(i)?.joinToString(",", prefix = ",\"seatIds\":[", postfix = "]").orEmpty()
            "{\"cells\":\"$cells\"$ids}"
        }
        val next = nextSeatId?.let { ",\"nextSeatId\":$it" }.orEmpty()
        return "{\"formatVersion\":1$next,\"rows\":[${rows.joinToString(",")}]}"
    }

    private fun assertTooLarge(json: String) {
        assertThrows(LayoutTooLargeException::class.java, ThrowingRunnable { LayoutJson.decode(json) })
        assertNull(LayoutJson.decodeOrNull(json))
    }

    @Test
    fun moreRowsThanTheLimitAreTooLarge() {
        assertTooLarge(json(List(MAX_ROWS + 1) { "S" }))
        assertTooLarge(json(List(1000) { "S" }))
    }

    @Test
    fun moreColumnsThanTheLimitAreTooLarge() {
        assertTooLarge(json(listOf("S".repeat(MAX_COLUMNS + 1))))
        assertTooLarge(json(listOf("SS", "S".repeat(MAX_COLUMNS + 1))))
    }

    @Test
    fun theSizeIsCheckedBeforeTheCellsAreRead() {
        // The unknown cell 'X' would make the layout unreadable; the size is refused first.
        assertTooLarge(json(List(MAX_ROWS + 1) { "X" }))
        assertTooLarge(json(listOf("X".repeat(MAX_COLUMNS + 1))))
    }

    @Test
    fun seatIdsAboveTheLimitAreTooLarge() {
        assertTooLarge(json(listOf("S"), nextSeatId = MAX_SEAT_ID + 1, seatIds = listOf(listOf(1))))
        assertTooLarge(json(listOf("S"), nextSeatId = Int.MAX_VALUE, seatIds = listOf(listOf(1))))
        assertTooLarge(json(listOf("S"), seatIds = listOf(listOf(MAX_SEAT_ID))))
        assertTooLarge(json(listOf("S"), seatIds = listOf(listOf(Int.MAX_VALUE))))
        // Seats without ids get ids after the highest one: past the limit too.
        assertTooLarge("{\"formatVersion\":1,\"rows\":[{\"cells\":\"S\",\"seatIds\":[${MAX_SEAT_ID - 1}]},{\"cells\":\"S\"}]}")
    }

    @Test
    fun theLargestGridWithTheHighestIdsIsRead() {
        val seats = MAX_ROWS * MAX_COLUMNS
        val first = MAX_SEAT_ID - seats
        val ids = List(MAX_ROWS) { r -> List(MAX_COLUMNS) { c -> first + r * MAX_COLUMNS + c } }
        val layout = LayoutJson.decode(json(List(MAX_ROWS) { "S".repeat(MAX_COLUMNS) }, nextSeatId = MAX_SEAT_ID, seatIds = ids))
        assertEquals(MAX_ROWS, layout.rowCount)
        assertEquals(MAX_COLUMNS, layout.columnCount)
        assertEquals(MAX_SEAT_ID, layout.nextSeatId)
        // Without nextSeatId it follows the highest id.
        assertEquals(MAX_SEAT_ID, LayoutJson.decode(json(listOf("S"), seatIds = listOf(listOf(MAX_SEAT_ID - 1)))).nextSeatId)
    }

    @Test
    fun otherDefectsStayUnreadableNotTooLarge() {
        val e = assertThrows(IllegalArgumentException::class.java, ThrowingRunnable { LayoutJson.decode(json(listOf("X"))) })
        assertTrue(e !is LayoutTooLargeException)
    }

    /** No layout the editor can make comes near the JSON limit the repositories and backups enforce. */
    @Test
    fun aWorstCaseLayoutStaysFarBelowTheJsonLimit() {
        // Seat, aisle, seat, …: the most blocks a row can have, each with a start number.
        val pattern = (0 until MAX_COLUMNS).joinToString("") { if (it % 2 == 0) "S" else "|" }
        var layout = SeatLayout.fromPatterns(List(MAX_ROWS) { pattern }, firstSeatId = MAX_SEAT_ID - MAX_ROWS * MAX_COLUMNS)
        for (r in 0 until MAX_ROWS) {
            layout = layout.updateRow(r) { row ->
                row.copy(
                    label = "R${r}\u00e4\u00f6\u00fc".take(6),
                    numbering = SeatNumbering.RESTART_PER_BLOCK,
                    numberingDirection = NumberingDirection.RIGHT_TO_LEFT,
                    blockStartNumbers = List(row.blocks.size) { Int.MAX_VALUE },
                    halfSeatOffset = true,
                )
            }
            for (c in 0 until MAX_COLUMNS step 2) layout = layout.withFixedLabel(r, c, "\"\\\u00e4\u00f6")
        }
        val full = SeatLayout.fromPatterns(List(MAX_ROWS) { "S".repeat(MAX_COLUMNS) }, firstSeatId = MAX_SEAT_ID - MAX_ROWS * MAX_COLUMNS)
        var labelled = full
        for (r in 0 until MAX_ROWS) {
            labelled = labelled.updateRow(r) { it.copy(label = "R1\u00e4\u00f6\u00fc\u00df", numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }
            for (c in 0 until MAX_COLUMNS) labelled = labelled.withFixedLabel(r, c, "\"\\\u00e4\u00f6")
        }
        for (worst in listOf(layout, labelled)) {
            val length = LayoutJson.encode(worst).length
            assertTrue("$length chars", length < 100_000)
            assertTrue(length < LayoutJson.MAX_JSON_LENGTH / 2)
            assertEquals(worst, LayoutJson.decode(LayoutJson.encode(worst)))
        }
        assertEquals(SeatId(MAX_SEAT_ID - 1), labelled.rows.last().seatIds.last())
    }
}
