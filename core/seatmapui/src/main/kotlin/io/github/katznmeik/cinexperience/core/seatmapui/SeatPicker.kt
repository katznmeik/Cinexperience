package io.github.katznmeik.cinexperience.core.seatmapui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.model.LayoutLabels
import io.github.katznmeik.cinexperience.core.model.LayoutRow
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.labels
import io.github.katznmeik.cinexperience.core.ui.component.ChoiceChip
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** One seat the [SeatPicker] offers. */
data class SeatChoice(val seatId: SeatId, val label: SeatLabel, val block: Int) {
    /**
     * Short chip text: the seat's number, or its fixed label ("R"), nothing else.
     * The chip shows a special kind's icon beside it; the kind is not part of this text.
     */
    val shortText: String get() = label.seat
}

/**
 * One step of the [SeatPicker]'s row choice: a named grid row with its seats,
 * left to right as drawn on the map (whatever the row's numbering direction, so a
 * right-to-left row reads "6 5 4 3 2 1 R" like the map and Yorck's plan above it),
 * or the group of every seat in rows without a name (spec §4.3), by seat number.
 *
 * @property rowIndex the grid row; for the group of unnamed rows, its frontmost row.
 * @property label the row's label; `null` for the group of seats in rows without a name.
 */
data class SeatRowChoice(val rowIndex: Int, val label: String?, val seats: List<SeatChoice>) {
    /** The group of seats in rows without a name ("Seat 12"), not a named row. */
    val isUnnamed: Boolean get() = label == null

    /** Chip text of the row step: the row's label, "Seats" for the unnamed group. */
    val chipText: String get() = label ?: UNNAMED_GROUP_CHIP

    /** What a screen reader announces for the row chip: "Row 7", "Seats without a row". */
    val chipDescription: String get() = label?.let { "Row $it" } ?: UNNAMED_GROUP_TITLE

    /** The row's blocks (split by aisles), left to right, each with its seats. One block for the unnamed group. */
    val blocks: List<List<SeatChoice>> get() = seats.groupBy { it.block }.toSortedMap().values.toList()

    /** Caption of the [index]-th of the row's [blocks], `null` if the row is not split. */
    fun blockCaption(index: Int): String? = blockName(index, blocks.size)

    /**
     * What a screen reader announces for [seat]: its full label, plus the block in
     * split rows, where numbers may repeat ("Row 7, Seat 1, left block").
     */
    fun descriptionOf(seat: SeatChoice): String {
        val index = blocks.indexOfFirst { block -> block.any { it.seatId == seat.seatId } }
        val caption = blockName(index, blocks.size) ?: return seat.label.description
        return "${seat.label.description}, ${caption.lowercase()} block"
    }
}

/** "Left" / "Right" for two blocks, "Left" / "Centre" / "Right" for three, else "Block n"; `null` for one. */
fun blockName(index: Int, blockCount: Int): String? = when {
    blockCount <= 1 || index < 0 -> null
    blockCount == 2 -> if (index == 0) "Left" else "Right"
    blockCount == 3 -> listOf("Left", "Centre", "Right")[index]
    else -> "Block ${index + 1}"
}

private const val UNNAMED_GROUP_CHIP = "Seats"
private const val UNNAMED_GROUP_TITLE = "Seats without a row"

/**
 * The seats of a layout grouped by grid row, front to back, for choosing a seat
 * by row and number instead of tapping the map (accessibility; the visit form's
 * seat picker). Rows without seats are left out. Labels need not be unique, so
 * every named grid row is its own choice.
 *
 * Rows without a name ([LayoutRow.hasNoName], e.g. the double sofas of Kant Kino
 * Saal 3, each its own unnamed row) can't be told apart by a row label, so all
 * their seats form one group (label `null`, at the place of the frontmost such
 * row), ordered by seat number (fixed labels after the numbers; equal numbers in
 * map order, front to back and left to right), in one block.
 */
fun seatChoices(labels: LayoutLabels): List<SeatRowChoice> {
    val byRow = labels.seats.values.groupBy { it.position.row }.toSortedMap()
    val unnamed = byRow.filterKeys { labels.rowLabels[it] == null }
    val choices = byRow.filterKeys { it !in unnamed }.map { (row, seats) ->
        SeatRowChoice(
            rowIndex = row,
            label = labels.rowLabels[row],
            seats = seats.sortedBy { it.position.column }.map { SeatChoice(it.id, it.label, it.block) },
        )
    }
    if (unnamed.isEmpty()) return choices
    val group = SeatRowChoice(
        rowIndex = unnamed.keys.min(),
        label = null,
        seats = unnamed.values.flatten()
            .sortedWith(compareBy({ it.number ?: Int.MAX_VALUE }, { it.position.row }, { it.position.column }))
            .map { SeatChoice(it.id, it.label, block = 0) },
    )
    return (choices + group).sortedBy { it.rowIndex }
}

fun seatChoices(layout: SeatLayout): List<SeatRowChoice> = seatChoices(layout.labels())

/**
 * Choose a seat by row, then by number: an accessible alternative to tapping the
 * [SeatMap]. Plain chips (no dialog), each announced with the seat's full label.
 * Split rows show their blocks separately (numbers may restart per block). When
 * [selectedSeatId] changes (e.g. tapped on the map), the shown row follows it.
 * Seats in rows without a name are one group ([seatChoices]): a "Seats" chip
 * beside the rows, or, in a hall where no row has a name, the seats alone
 * without a row step.
 *
 * @param seatVisuals the map's seat states; when not empty, each chip also
 *   announces its seat's state ("Row 7, Seat 10, great, favourite").
 * @param bookingSeatIds the seats of an imported ticket's booking (spec §6.10): their
 *   chips carry a ticket mark after the number and are announced "…, in this booking".
 */
@Composable
fun SeatPicker(
    choices: List<SeatRowChoice>,
    selectedSeatId: SeatId?,
    onSeatSelected: (SeatId) -> Unit,
    modifier: Modifier = Modifier,
    seatVisuals: Map<SeatId, SeatVisual> = emptyMap(),
    bookingSeatIds: Set<SeatId> = emptySet(),
) {
    if (choices.isEmpty()) return
    val initialRow = remember(choices, selectedSeatId) {
        choices.indexOfFirst { row -> row.seats.any { it.seatId == selectedSeatId } }.coerceAtLeast(0)
    }
    var rowIndex by rememberSaveable(choices.size) { mutableIntStateOf(initialRow) }
    LaunchedEffect(choices, selectedSeatId) {
        val selectedRow = choices.indexOfFirst { row -> row.seats.any { it.seatId == selectedSeatId } }
        if (selectedRow >= 0) rowIndex = selectedRow
    }
    val row = choices[rowIndex.coerceIn(choices.indices)]
    // A hall whose rows have no names (e.g. Kant Kino Saal 3) needs no row step.
    val onlyUnnamed = choices.size == 1 && row.isUnnamed
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!onlyUnnamed) {
            PickerHeader("Row")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                choices.forEachIndexed { index, choice ->
                    ChoiceChip(
                        text = choice.chipText,
                        selected = index == rowIndex,
                        onClick = { rowIndex = index },
                        modifier = Modifier.semantics { contentDescription = choice.chipDescription },
                    )
                }
            }
        }
        PickerHeader(
            when {
                onlyUnnamed -> "Seat"
                row.label == null -> UNNAMED_GROUP_TITLE
                else -> "Seat in row ${row.label}"
            },
        )
        row.blocks.forEachIndexed { index, block ->
            row.blockCaption(index)?.let { caption ->
                Text(caption, style = MaterialTheme.typography.labelSmall, color = CinexTheme.colors.textMuted)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                block.forEach { seat ->
                    val booked = seat.seatId in bookingSeatIds
                    val state = if (seatVisuals.isEmpty()) {
                        row.descriptionOf(seat)
                    } else {
                        "${row.descriptionOf(seat)}, ${(seatVisuals[seat.seatId] ?: SeatVisual.Neutral).stateText}"
                    }
                    val description = if (booked) state + IN_THIS_BOOKING else state
                    ChoiceChip(
                        text = seat.shortText,
                        selected = seat.seatId == selectedSeatId,
                        onClick = { onSeatSelected(seat.seatId) },
                        icon = seat.label.kind.icon,
                        trailingIcon = if (booked) CinexIcons.Ticket else null,
                        modifier = Modifier.semantics { contentDescription = description },
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = CinexTheme.colors.textMuted,
        modifier = Modifier.semantics { heading() },
    )
}

/** Icon of a non-standard seat kind, `null` for standard seats. */
val SeatKind.icon
    get() = when (this) {
        SeatKind.STANDARD -> null
        SeatKind.WHEELCHAIR -> CinexIcons.Wheelchair
        SeatKind.COMPANION -> CinexIcons.CompanionSeat
        SeatKind.SOFA -> CinexIcons.Sofa
    }
