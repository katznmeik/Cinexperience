package io.github.katznmeik.cinexperience.core.model

/**
 * The printed label of a seat, e.g. row "6", seat 12.
 *
 * Wheelchair spaces are not counted in the seat numbering; their [number] counts
 * the wheelchair spaces of the row instead ("Row 1, Wheelchair 2").
 */
data class SeatLabel(val row: String, val number: Int, val kind: SeatKind = SeatKind.STANDARD) {
    /** English display form, e.g. "Row 6, Seat 12" or "Row 1, Wheelchair 2". */
    val displayText: String
        get() = if (kind == SeatKind.WHEELCHAIR) "Row $row, Wheelchair $number" else "Row $row, Seat $number"

    /**
     * [displayText] that also names a companion or sofa seat, e.g.
     * "Row 6, Seat 12, companion seat". For accessibility and seat details.
     */
    val description: String
        get() = when (kind) {
            SeatKind.STANDARD, SeatKind.WHEELCHAIR -> displayText
            SeatKind.COMPANION -> "$displayText, companion seat"
            SeatKind.SOFA -> "$displayText, sofa"
        }
}

/**
 * Everything derived about one seat from the layout.
 *
 * @property block index of the seat's block within its row (0-based, left to right).
 */
data class SeatInfo(
    val id: SeatId,
    val position: CellPosition,
    val block: Int,
    val label: SeatLabel,
)

/**
 * Derived labels of a whole layout.
 *
 * @property rowLabels one entry per grid row; `null` for a row without seats and
 *   without a custom label (e.g. a cross aisle).
 * @property seats every seat of the layout, in row-major order.
 */
data class LayoutLabels(
    val rowLabels: List<String?>,
    val seats: Map<SeatId, SeatInfo>,
) {
    fun labelOf(seatId: SeatId): SeatLabel? = seats[seatId]?.label
}

/**
 * Derives row labels and seat numbers (spec §4.3).
 *
 * Rows:
 * - A row's custom [LayoutRow.label] wins.
 * - Otherwise rows that contain at least one seat (of any [SeatKind]) are
 *   numbered "1", "2", … from the screen backwards. The counter runs over all
 *   seat rows, including those with a custom label, so it always equals the seat
 *   row's position. Rows without seats (e.g. cross aisles) are skipped and get no
 *   label.
 *
 * Seats, per row, left to right (facing the screen), skipping gaps and aisles:
 * - A row is split into *blocks* by AISLE cells (only aisles split; gaps do not).
 *   Leading or trailing aisles do not create empty blocks.
 * - Wheelchair spaces ([SeatKind.isNumbered] false) are not counted: they get no
 *   seat number and do not advance the numbering. Instead they are counted
 *   1, 2, … per row, left to right ("Wheelchair 2"). Companion and sofa seats are
 *   numbered like standard seats.
 * - The first numbered seat of block `b` gets `blockStartNumbers[b]` if set.
 *   Otherwise the first numbered seat of the row gets 1; in a later block it gets
 *   1 with [SeatNumbering.RESTART_PER_BLOCK], or the previous numbered seat's
 *   number + 1 with [SeatNumbering.CONTINUOUS].
 * - Numbers increase by one within a block.
 *
 * Labels are not required to be unique; the [SeatId] is the identity.
 */
fun SeatLayout.labels(): LayoutLabels {
    val rowLabels = ArrayList<String?>(rowCount)
    val seats = LinkedHashMap<SeatId, SeatInfo>()
    var seatRowCounter = 0
    rows.forEachIndexed { rowIndex, row ->
        val hasSeats = row.cells.any { it is LayoutCell.Seat }
        if (hasSeats) seatRowCounter++
        val rowLabel = row.label ?: if (hasSeats) seatRowCounter.toString() else null
        rowLabels += rowLabel
        if (rowLabel == null) return@forEachIndexed

        var block = -1
        var aisleSinceLastSeat = false
        var number = 0
        var rowHasNumberedSeat = false
        var blockHasNumberedSeat = false
        var wheelchairs = 0
        row.cells.forEachIndexed { column, cell ->
            when (cell) {
                LayoutCell.Aisle -> if (block >= 0) aisleSinceLastSeat = true
                LayoutCell.Gap -> Unit
                is LayoutCell.Seat -> {
                    if (block < 0 || aisleSinceLastSeat) {
                        block++
                        aisleSinceLastSeat = false
                        blockHasNumberedSeat = false
                    }
                    val label = if (cell.kind.isNumbered) {
                        number = when {
                            blockHasNumberedSeat -> number + 1
                            else -> row.blockStartNumbers.getOrNull(block)
                                ?: if (!rowHasNumberedSeat || row.numbering == SeatNumbering.RESTART_PER_BLOCK) 1 else number + 1
                        }
                        rowHasNumberedSeat = true
                        blockHasNumberedSeat = true
                        SeatLabel(rowLabel, number, cell.kind)
                    } else {
                        SeatLabel(rowLabel, ++wheelchairs, cell.kind)
                    }
                    seats[cell.id] = SeatInfo(cell.id, CellPosition(rowIndex, column), block, label)
                }
            }
        }
    }
    return LayoutLabels(rowLabels, seats)
}
