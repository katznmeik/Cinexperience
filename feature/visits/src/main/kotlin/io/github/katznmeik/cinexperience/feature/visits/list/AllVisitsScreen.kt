package io.github.katznmeik.cinexperience.feature.visits.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.model.VisitListItem
import io.github.katznmeik.cinexperience.core.ui.component.BackTopBar
import io.github.katznmeik.cinexperience.core.ui.component.RowDivider
import io.github.katznmeik.cinexperience.core.ui.component.ScreenTitle
import io.github.katznmeik.cinexperience.core.ui.component.StarRatingDisplay
import io.github.katznmeik.cinexperience.core.ui.text.countText
import io.github.katznmeik.cinexperience.core.ui.text.dateText
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.visits.UNTITLED
import io.github.katznmeik.cinexperience.feature.visits.shortText

@Composable
internal fun AllVisitsEntry(viewModel: AllVisitsViewModel, onBack: () -> Unit, onOpenVisit: (Long) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AllVisitsScreen(state, onBack, onOpenVisit)
}

/** Second line of a visit row: "delphi LUX · Saal 1". */
internal fun VisitListItem.placeText(): String = "$cinemaName · $hallName"

/** Third line: "12 Sep 2026 · Row G · Seat 10 · OmU"; parts that are missing are left out. */
internal fun VisitListItem.detailText(): String = listOfNotNull(
    dateText(visit.date),
    seatLabel?.shortText,
    visit.screeningFormat?.displayName,
).joinToString(" · ")

/** All visits (spec §6.8): newest first; tap to edit. */
@Composable
internal fun AllVisitsScreen(
    state: AllVisitsUiState,
    onBack: () -> Unit,
    onOpenVisit: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "top") { BackTopBar(onBack) }
        item(key = "title") {
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ScreenTitle("All visits")
                if (!state.isLoading && state.errorMessage == null) {
                    Text(
                        text = if (state.visits.isEmpty()) "No visits yet" else "${countText(state.visits.size, "visit")} · newest first",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinexTheme.colors.textMuted,
                    )
                }
            }
        }
        when {
            state.errorMessage != null -> item(key = "error") { InfoText(state.errorMessage) }
            !state.isLoading && state.visits.isEmpty() -> item(key = "empty") {
                InfoText("Visits you log with \"Add visit\" on the Cinemas tab or a hall's page appear here.")
            }
            else -> itemsIndexed(state.visits, key = { _, item -> item.visit.id }) { index, item ->
                Column(Modifier.padding(horizontal = 12.dp)) {
                    if (index > 0) RowDivider()
                    VisitRow(item, onClick = { onOpenVisit(item.visit.id) })
                }
            }
        }
    }
}

@Composable
private fun VisitRow(item: VisitListItem, onClick: () -> Unit) {
    val visit = item.visit
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = visit.filmTitle ?: UNTITLED,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (visit.filmTitle == null) CinexTheme.colors.textMuted else CinexTheme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(item.placeText(), style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.detailText(), style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (visit.seatRating != null) StarRatingDisplay(visit.seatRating?.value)
    }
}

@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = CinexTheme.colors.textMuted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}
