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
    /** Short chip text: the seat number, or "W2" for the second wheelchair space. */
    val shortText: String get() = if (label.kind == SeatKind.WHEELCHAIR) "W${label.number}" else label.number.toString()
}

/** One grid row with its seats, left to right. */
data class SeatRowChoice(val rowIndex: Int, val label: String, val seats: List<SeatChoice>) {
    /** The row's blocks (split by aisles), left to right, each with its seats. */
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

/**
 * The seats of a layout grouped by grid row, front to back, for choosing a seat
 * by row and number instead of tapping the map (accessibility; the visit form's
 * seat picker). Rows without seats are left out. Labels need not be unique, so
 * every grid row is its own choice.
 */
fun seatChoices(labels: LayoutLabels): List<SeatRowChoice> =
    labels.seats.values
        .groupBy { it.position.row }
        .toSortedMap()
        .map { (row, seats) ->
            SeatRowChoice(
                rowIndex = row,
                label = labels.rowLabels[row].orEmpty(),
                seats = seats.sortedBy { it.position.column }.map { SeatChoice(it.id, it.label, it.block) },
            )
        }

fun seatChoices(layout: SeatLayout): List<SeatRowChoice> = seatChoices(layout.labels())

/** The first seat in a row labelled [rowLabel] whose label matches [number] and [kind]. */
fun List<SeatRowChoice>.findSeat(rowLabel: String, number: Int, kind: SeatKind = SeatKind.STANDARD): SeatId? =
    asSequence()
        .filter { it.label.equals(rowLabel.trim(), ignoreCase = true) }
        .flatMap { it.seats }
        .firstOrNull { it.label.number == number && (it.label.kind == SeatKind.WHEELCHAIR) == (kind == SeatKind.WHEELCHAIR) }
        ?.seatId

/**
 * Choose a seat by row, then by number: an accessible alternative to tapping the
 * [SeatMap]. Plain chips (no dialog), each announced with the seat's full label.
 * Split rows show their blocks separately (numbers may restart per block). When
 * [selectedSeatId] changes (e.g. tapped on the map), the shown row follows it.
 */
@Composable
fun SeatPicker(
    choices: List<SeatRowChoice>,
    selectedSeatId: SeatId?,
    onSeatSelected: (SeatId) -> Unit,
    modifier: Modifier = Modifier,
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PickerHeader("Row")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            choices.forEachIndexed { index, choice ->
                ChoiceChip(
                    text = choice.label,
                    selected = index == rowIndex,
                    onClick = { rowIndex = index },
                    modifier = Modifier.semantics { contentDescription = "Row ${choice.label}" },
                )
            }
        }
        PickerHeader("Seat in row ${row.label}")
        row.blocks.forEachIndexed { index, block ->
            row.blockCaption(index)?.let { caption ->
                Text(caption, style = MaterialTheme.typography.labelSmall, color = CinexTheme.colors.textMuted)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                block.forEach { seat ->
                    val description = row.descriptionOf(seat)
                    ChoiceChip(
                        text = seat.shortText,
                        selected = seat.seatId == selectedSeatId,
                        onClick = { onSeatSelected(seat.seatId) },
                        icon = seat.label.kind.icon,
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
