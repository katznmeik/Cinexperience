package io.github.katznmeik.cinexperience.feature.visits.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.model.LayoutLabels
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.seatmapui.BookingLegend
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMap
import io.github.katznmeik.cinexperience.core.seatmapui.SeatPicker
import io.github.katznmeik.cinexperience.core.seatmapui.rememberSeatMapViewState
import io.github.katznmeik.cinexperience.core.seatmapui.seatChoices
import io.github.katznmeik.cinexperience.core.seatmapui.seatMapDescription
import io.github.katznmeik.cinexperience.core.ui.component.SectionHeader
import io.github.katznmeik.cinexperience.core.ui.component.TopBarIconButton
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/**
 * Full-screen seat picker of the visit form: the hall's seat map (tap a seat to
 * select it, tap again to deselect) and, below it, the same choice by row and
 * number for screen readers and precise picking. "Use seat" takes the selection
 * into the form; with nothing selected it leaves the visit without a seat.
 */
@Composable
internal fun SeatPickerScreen(state: VisitEditUiState, picker: SeatPickerState, actions: VisitEditActions) {
    val layout = state.layout ?: return
    val labels = state.labels ?: return
    val choices = remember(labels) { seatChoices(labels) }
    val selected = picker.seatId?.let { labels.labelOf(it) }
    val viewState = rememberSeatMapViewState()
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopBarIconButton(icon = CinexIcons.Close, contentDescription = "Close seat picker", onClick = actions::onPickerClose)
            Text(
                text = "YOUR SEAT",
                style = MaterialTheme.typography.titleLarge,
                color = CinexTheme.colors.text,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
                    .semantics { heading() },
            )
            Button(
                onClick = actions::onPickerConfirm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CinexTheme.colors.text,
                    contentColor = CinexTheme.colors.onPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
                Text(if (picker.seatId == null) "No seat" else "Use seat", style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = listOfNotNull(state.cinemaName, state.hallName).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = CinexTheme.colors.textMuted,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            if (state.seatMissing && !state.layoutUnreadable) {
                Text(
                    text = "Your saved seat is no longer in this hall's layout. Pick a seat, or choose \"No seat\" to remove it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinexTheme.colors.text,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CinexTheme.colors.surface)
                    // Narrow side padding: the seats (and their numbers) get the width.
                    .padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val booking = state.bookingSeatIds
                SeatMap(
                    layout = layout,
                    labels = labels,
                    contentDescription = seatMapDescription(state.hallName.orEmpty(), labels) + bookingDescription(booking, labels),
                    screenWidth = state.screenWidth,
                    screenDistance = state.screenDistance,
                    screenOffset = state.screenOffset,
                    selectedSeatId = picker.seatId,
                    bookingSeatIds = booking,
                    onSeatClick = actions::onPickerTap,
                    maxHeight = 560.dp,
                    viewState = viewState,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Several seats in the booking (spec §6.10): which they are, so another one can be picked.
                if (booking.isNotEmpty()) BookingLegend(Modifier.padding(top = 12.dp))
                Text(
                    text = selected?.shortText?.uppercase() ?: "Tap your seat. Pinch to zoom.",
                    style = if (selected != null) CinexTheme.textStyles.cardTitle else MaterialTheme.typography.bodySmall,
                    color = if (selected != null) CinexTheme.colors.text else CinexTheme.colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // A hall whose rows have no names (Kant Kino Saal 3) lists its seats by number alone.
                val byNumberOnly = choices.isNotEmpty() && choices.all { it.isUnnamed }
                SectionHeader(if (byNumberOnly) "Or choose by number" else "Or choose by row and number")
                SeatPicker(
                    choices = choices,
                    selectedSeatId = picker.seatId,
                    bookingSeatIds = state.bookingSeatIds,
                    onSeatSelected = actions::onPickerChoose,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** " Seats in this booking: Row 7, Seat 10; Row 7, Seat 11." for the map's description; empty without any. */
internal fun bookingDescription(booking: Set<SeatId>, labels: LayoutLabels): String {
    val seats = booking.mapNotNull { labels.labelOf(it)?.description }
    if (seats.isEmpty()) return ""
    return " ${if (seats.size == 1) "Seat" else "Seats"} in this booking: ${seats.joinToString("; ")}."
}
