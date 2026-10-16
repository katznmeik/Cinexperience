package io.github.katznmeik.cinexperience.core.model

import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_COLUMNS
import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_ROWS
import io.github.katznmeik.cinexperience.core.model.SeatLayout.Companion.MAX_SEAT_ID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Running out of seat ids below [MAX_SEAT_ID] is its own exception,
 * [SeatIdsExhaustedException], so the layout editor can refuse just that edit
 * while every other refused edit (a bug) still fails loudly.
 */
class SeatIdsExhaustedTest {

    private val full = SeatLayout.fromPatterns("S.", firstSeatId = MAX_SEAT_ID - 1) // next id = MAX_SEAT_ID

    @Test
    fun editsThatNeedAnIdPastTheLimitAreExhausted() {
        assertFailsWith<SeatIdsExhaustedException> { full.withCellType(0, 1, CellType.SEAT) }
        assertFailsWith<SeatIdsExhaustedException> { full.cycleCell(0, 1).cycleCell(0, 1) } // gap → aisle → seat
        assertFailsWith<SeatIdsExhaustedException> { full.insertRow(1) }
        assertFailsWith<SeatIdsExhaustedException> { full.insertColumn(2) }
        assertFailsWith<SeatIdsExhaustedException> { full.resize(2, 2) }
        assertFailsWith<SeatIdsExhaustedException> { full.withSeat(0, 1, SeatId(MAX_SEAT_ID)) }
        assertFailsWith<SeatIdsExhaustedException> { full.withSeat(0, 1, SeatId(Int.MAX_VALUE)) }
    }

    @Test
    fun newGridsAndShiftsPastTheLimitAreExhausted() {
        assertFailsWith<SeatIdsExhaustedException> { SeatLayout.create(1, 2, firstSeatId = MAX_SEAT_ID - 1) }
        assertFailsWith<SeatIdsExhaustedException> { SeatLayout.create(1, 1, firstSeatId = MAX_SEAT_ID + 1, fill = CellType.GAP) }
        assertFailsWith<SeatIdsExhaustedException> { SeatLayout.create(2, 2, firstSeatId = Int.MAX_VALUE - 1) }
        assertFailsWith<SeatIdsExhaustedException> { SeatLayout.fromPatterns("SS", firstSeatId = MAX_SEAT_ID - 1) }
        assertFailsWith<SeatIdsExhaustedException> { SeatLayout.fromPatterns("..", firstSeatId = Int.MAX_VALUE) }
        val layout = SeatLayout.fromPatterns("SSS", firstSeatId = 1)
        assertFailsWith<SeatIdsExhaustedException> { layout.withIdsShiftedBy(MAX_SEAT_ID - 3) }
        assertFailsWith<SeatIdsExhaustedException> { layout.withIdsShiftedBy(Int.MAX_VALUE) }
        val nearTheLimit = Hall(cinemaId = 1, name = "Saal 1", nextSeatId = MAX_SEAT_ID - 5)
        assertFailsWith<SeatIdsExhaustedException> { nearTheLimit.startLayoutEdit().newGrid(10, 16) }
        val session = Hall(cinemaId = 1, name = "Saal 1", layout = full, nextSeatId = MAX_SEAT_ID).startLayoutEdit()
        assertFailsWith<SeatIdsExhaustedException> { session.setSeat(0, 1, SeatKind.WHEELCHAIR) }
    }

    @Test
    fun otherRefusedEditsAreNotExhaustion() {
        val layout = SeatLayout.fromPatterns("SS", firstSeatId = 1)
        val others: List<() -> Unit> = listOf(
            { layout.withSeat(0, 0, SeatId(5)) }, // already a seat
            { layout.withCellType(0, 1, CellType.GAP).withSeat(0, 1, SeatId(1)) }, // id in use
            { layout.removeRow(0) }, // the only row
            { layout.withIdsShiftedBy(-1) },
            { layout.updateRow(0) { it.copy(cells = listOf(LayoutCell.Gap, LayoutCell.Gap)) } },
            { SeatLayout.create(MAX_ROWS, 1, firstSeatId = 1).insertRow(0) }, // too many rows
            { SeatLayout.create(1, MAX_COLUMNS + 1, firstSeatId = 1) },
            { SeatLayout.create(1, 1, firstSeatId = 0) },
        )
        for (edit in others) {
            val e = assertFailsWith<IllegalArgumentException> { edit() }
            assertFalse(e is SeatIdsExhaustedException, "Not exhaustion: ${e.message}")
        }
    }
}
