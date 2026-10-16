package io.github.katznmeik.cinexperience.core.model

/**
 * The printed label of a seat (spec §4.3): row "7", seat "10", or row "8", seat
 * "R" for a seat with a fixed label ([LayoutCell.Seat.fixedLabel]).
 *
 * @property row the row's label; `null` for a row without a name
 *   ([LayoutRow.hasNoName]): the seat is then shown by seat alone ("Seat 12").
 * @property seat the seat's number as text, or its fixed label.
 * @property kind wheelchair spaces, companion seats and sofa places are numbered
 *   like standard seats; the kind is only named in [description].
 */
data class SeatLabel(val row: String?, val seat: String, val kind: SeatKind = SeatKind.STANDARD) {
    /** The label of a numbered seat. */
    constructor(row: String?, number: Int, kind: SeatKind = SeatKind.STANDARD) : this(row, number.toString(), kind)

    /** English display form, e.g. "Row 6, Seat 12", "Row 8, Seat R", or "Seat 12" in a row without a name. */
    val displayText: String get() = if (row == null) "Seat $seat" else "Row $row, Seat $seat"

    /** [displayText] with " · " between row and seat: "Row 7 · Seat 10", "Seat 12". For titles and lists. */
    val shortText: String get() = if (row == null) "Seat $seat" else "Row $row · Seat $seat"

    /**
     * [displayText] that also names a wheelchair space, companion or sofa seat, e.g.
     * "Row 1, Seat 2, wheelchair space" or "Seat 12, sofa". For accessibility and seat details.
     */
    val description: String
        get() = when (kind) {
            SeatKind.STANDARD -> displayText
            SeatKind.WHEELCHAIR -> "$displayText, wheelchair space"
            SeatKind.COMPANION -> "$displayText, companion seat"
            SeatKind.SOFA -> "$displayText, sofa"
        }
}

/**
 * Everything derived about one seat from the layout.
 *
 * @property block index of the seat's block within its row (0-based, counted from
 *   the left as stored, whatever the row's numbering direction).
 * @property number the seat's number in its row's numbering; `null` for a seat
 *   with a fixed label (its [label] then shows that label).
 */
data class SeatInfo(
    val id: SeatId,
    val position: CellPosition,
    val block: Int,
    val label: SeatLabel,
    val number: Int?,
)

/**
 * Derived labels of a whole layout.
 *
 * @property rowLabels one entry per grid row, the label drawn beside it; `null` for
 *   a row without seats and without a custom label (e.g. a cross aisle) and for a
 *   row without a name ([LayoutRow.hasNoName]). Which rows have seats is in [seats].
 * @property seats every seat of the layout, in row-major order (each row left to right).
 */
data class LayoutLabels(
    val rowLabels: List<String?>,
    val seats: Map<SeatId, SeatInfo>,
) {
    fun labelOf(seatId: SeatId): SeatLabel? = seats[seatId]?.label
}

/**
 * Derives row labels and seat numbers (spec §4.3). They must match Yorck's
 * booking exactly.
 *
 * Rows:
 * - A row's custom [LayoutRow.label] wins. A row without a name
 *   ([LayoutRow.hasNoName], stored label "-") gets no label: its entry in
 *   [LayoutLabels.rowLabels] and its seats' [SeatLabel.row] are `null`.
 * - Otherwise rows that contain at least one seat (of any [SeatKind]) are
 *   numbered "1", "2", … from the screen backwards. The counter runs over all
 *   seat rows, including those with a custom label, so it always equals the seat
 *   row's position. Rows without seats (e.g. cross aisles) are skipped and get no
 *   label.
 *
 * Seats, per row:
 * - Every seat is numbered, whatever its [SeatKind]: wheelchair spaces, companion
 *   seats and sofa places count like standard seats. Gaps and aisles are skipped.
 * - The row is numbered in its [LayoutRow.numberingDirection], as drawn with the
 *   screen at the top: [NumberingDirection.LEFT_TO_RIGHT] starts at the leftmost
 *   seat, [NumberingDirection.RIGHT_TO_LEFT] at the rightmost.
 * - A row is split into *blocks* by AISLE cells ([LayoutRow.blocks]; gaps don't
 *   split). Block `b` is counted from the left as stored, in both directions.
 *   Blocks are numbered in numbering order: with right to left, the rightmost
 *   block first, and within each block its rightmost seat first.
 * - A seat with a [LayoutCell.Seat.fixedLabel] shows that label and doesn't use
 *   up a number: the next seat gets the number it would have got without it.
 *   Every other seat is a *numbered* seat.
 * - The first numbered seat of block `b` (in numbering order) gets
 *   `blockStartNumbers[b]` if set. Otherwise the row's first numbered seat gets 1,
 *   and the first numbered seat of a later block gets 1 with
 *   [SeatNumbering.RESTART_PER_BLOCK], or the previous numbered seat's number + 1
 *   with [SeatNumbering.CONTINUOUS].
 * - Within a block numbers rise by one from seat to seat, across gaps.
 *
 * Examples (as drawn, screen at the top):
 * - `W.W|SSSSSSSSS|W.W`, left to right, continuous: 1 2 | 3 … 11 | 12 13.
 * - `SSSSSSW`, right to left, the `W` with fixed label "R": 6 5 4 3 2 1 R.
 * - `SSS|SSSS`, right to left, continuous: 7 6 5 | 4 3 2 1; with
 *   `blockStartNumbers = [10, null]`: 12 11 10 | 4 3 2 1.
 *
 * Labels are not required to be unique; the [SeatId] is the identity.
 */
fun SeatLayout.labels(): LayoutLabels {
    val rowLabels = ArrayList<String?>(rowCount)
    val seats = LinkedHashMap<SeatId, SeatInfo>()
    var seatRowCounter = 0
    rows.forEachIndexed { rowIndex, row ->
        val blocks = row.blocks
        if (blocks.isNotEmpty()) seatRowCounter++
        val rowLabel = when {
            row.hasNoName -> null
            else -> row.label ?: if (blocks.isNotEmpty()) seatRowCounter.toString() else null
        }
        rowLabels += rowLabel
        if (blocks.isEmpty()) return@forEachIndexed

        val numbers = row.numbersByColumn(blocks)
        blocks.forEachIndexed { block, columns ->
            for (column in columns) {
                val cell = row.cells[column] as LayoutCell.Seat
                val number = numbers[column]
                val text = cell.fixedLabel ?: number.toString()
                seats[cell.id] = SeatInfo(cell.id, CellPosition(rowIndex, column), block, SeatLabel(rowLabel, text, cell.kind), number)
            }
        }
    }
    return LayoutLabels(rowLabels, seats)
}

/**
 * The number of each numbered seat of this row by column (`null` for other cells
 * and fixed-label seats), assigned in numbering order by the rules of [labels].
 */
internal fun LayoutRow.numbersByColumn(blocks: List<List<Int>> = this.blocks): Array<Int?> {
    val numbers = arrayOfNulls<Int>(cells.size)
    val rightToLeft = numberingDirection == NumberingDirection.RIGHT_TO_LEFT
    var previous: Int? = null
    for (block in if (rightToLeft) blocks.indices.reversed() else blocks.indices) {
        var blockHasNumber = false
        for (column in if (rightToLeft) blocks[block].asReversed() else blocks[block]) {
            if ((cells[column] as LayoutCell.Seat).fixedLabel != null) continue
            val number = when {
                blockHasNumber -> previous!! + 1
                else -> blockStartNumbers.getOrNull(block)
                    ?: previous?.takeIf { numbering == SeatNumbering.CONTINUOUS }?.plus(1)
                    ?: 1
            }
            numbers[column] = number
            previous = number
            blockHasNumber = true
        }
    }
    return numbers
}
