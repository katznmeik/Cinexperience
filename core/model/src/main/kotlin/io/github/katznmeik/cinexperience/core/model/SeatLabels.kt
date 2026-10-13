package io.github.katznmeik.cinexperience.core.model

/** The printed label of a seat, e.g. row "F", seat 12. */
data class SeatLabel(val row: String, val number: Int) {
    /** English display form, e.g. "Row F, Seat 12". */
    val displayText: String get() = "Row $row, Seat $number"
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
 * - Otherwise rows that contain at least one seat are numbered "1", "2", … from
 *   the screen backwards. The counter runs over all seat rows, including those
 *   with a custom label, so it always equals the seat row's position. Rows
 *   without seats (e.g. cross aisles) are skipped and get no label.
 *
 * Seats, per row, left to right, skipping gaps and aisles:
 * - A row is split into *blocks* by AISLE cells (only aisles split; gaps do not).
 *   Leading or trailing aisles do not create empty blocks.
 * - Block `b` starts at `blockStartNumbers[b]` if set. Otherwise block 0 starts
 *   at 1; a later block starts at 1 with [SeatNumbering.RESTART_PER_BLOCK], or at
 *   the previous block's last number + 1 with [SeatNumbering.CONTINUOUS].
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
        row.cells.forEachIndexed { column, cell ->
            when (cell) {
                LayoutCell.Aisle -> if (block >= 0) aisleSinceLastSeat = true
                LayoutCell.Gap -> Unit
                is LayoutCell.Seat -> {
                    if (block < 0 || aisleSinceLastSeat) {
                        block++
                        aisleSinceLastSeat = false
                        number = row.blockStartNumbers.getOrNull(block)
                            ?: if (block == 0 || row.numbering == SeatNumbering.RESTART_PER_BLOCK) 1 else number + 1
                    } else {
                        number++
                    }
                    seats[cell.id] = SeatInfo(cell.id, CellPosition(rowIndex, column), block, SeatLabel(rowLabel, number))
                }
            }
        }
    }
    return LayoutLabels(rowLabels, seats)
}
