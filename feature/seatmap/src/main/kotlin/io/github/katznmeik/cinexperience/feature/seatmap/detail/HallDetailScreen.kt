package io.github.katznmeik.cinexperience.feature.seatmap.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMap
import io.github.katznmeik.cinexperience.core.seatmapui.SeatPicker
import io.github.katznmeik.cinexperience.core.seatmapui.seatChoices
import io.github.katznmeik.cinexperience.core.seatmapui.seatMapDescription
import io.github.katznmeik.cinexperience.core.seatmapui.viewingAngleText
import io.github.katznmeik.cinexperience.core.ui.component.BackTopBar
import io.github.katznmeik.cinexperience.core.ui.component.OutlinedPillButton
import io.github.katznmeik.cinexperience.core.ui.component.RowDivider
import io.github.katznmeik.cinexperience.core.ui.component.RowGroup
import io.github.katznmeik.cinexperience.core.ui.component.ScreenTitle
import io.github.katznmeik.cinexperience.core.ui.component.SectionHeader
import io.github.katznmeik.cinexperience.core.ui.component.StarRatingDisplay
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.text.countText
import io.github.katznmeik.cinexperience.core.ui.text.dateText
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

@Composable
internal fun HallDetailEntry(
    viewModel: HallDetailViewModel,
    onBack: () -> Unit,
    onEditLayout: (Long) -> Unit,
    onAddVisit: (Long) -> Unit,
    onOpenVisit: (Long) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HallDetailScreen(
        state = state,
        onBack = onBack,
        onEditLayout = onEditLayout,
        onAddVisit = onAddVisit,
        onOpenVisit = onOpenVisit,
        onSeatClick = viewModel::onSeatClick,
        onSeatChosen = viewModel::selectSeat,
    )
}

/** "delphi LUX · 183 seats · 4 visits"; without a layout "delphi LUX · no visits". */
internal fun hallMetaText(state: HallDetailUiState.Loaded): String = listOfNotNull(
    state.cinemaName,
    state.layout?.let { countText(it.seatCount, "seat") },
    if (state.visitCount == 0) "no visits" else countText(state.visitCount, "visit"),
).joinToString(" · ")

/** Hall detail (spec §6.3). Scores and the seat sheet follow in M4. */
@Composable
internal fun HallDetailScreen(
    state: HallDetailUiState,
    onBack: () -> Unit,
    onEditLayout: (Long) -> Unit,
    onAddVisit: (Long) -> Unit,
    onOpenVisit: (Long) -> Unit,
    onSeatClick: (SeatId) -> Unit,
    onSeatChosen: (SeatId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        when (state) {
            is HallDetailUiState.Loaded -> {
                BackTopBar(onBack) {
                    if (state.layout != null) EditLayoutAction(onClick = { onEditLayout(state.hallId) })
                }
                Header(state.hallName, hallMetaText(state))
                MapCard {
                    when {
                        state.layout != null -> MapContent(state, onSeatClick, onSeatChosen)
                        state.layoutUnreadable -> EmptyLayout(
                            title = "This layout can't be shown",
                            text = "It was saved by a newer version of the app or is damaged. " +
                                "You can draw it again; saving replaces the stored layout.",
                            button = "Redraw layout",
                            onClick = { onEditLayout(state.hallId) },
                        )
                        else -> EmptyLayout(
                            title = "No layout yet",
                            text = "Draw this hall's seats to see its seat map.",
                            button = "Draw layout",
                            onClick = { onEditLayout(state.hallId) },
                        )
                    }
                }
                VisitHistory(
                    visits = state.visits,
                    onAddVisit = { onAddVisit(state.hallId) },
                    onOpenVisit = onOpenVisit,
                )
            }
            HallDetailUiState.Loading -> BackTopBar(onBack)
            HallDetailUiState.NotFound -> MessageContent("This hall no longer exists.", onBack)
            is HallDetailUiState.Error -> MessageContent(state.message, onBack)
        }
    }
}

@Composable
private fun EditLayoutAction(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(CinexIcons.Grid, contentDescription = null, tint = CinexTheme.colors.text, modifier = Modifier.size(18.dp))
        Text("Edit layout", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium), color = CinexTheme.colors.text)
    }
}

@Composable
private fun Header(title: String, meta: String) {
    Column(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ScreenTitle(title)
        Text(meta, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.textMuted)
    }
}

@Composable
private fun MapCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CinexTheme.colors.surface)
            .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun ColumnScope.MapContent(
    state: HallDetailUiState.Loaded,
    onSeatClick: (SeatId) -> Unit,
    onSeatChosen: (SeatId) -> Unit,
) {
    val layout = state.layout ?: return
    val labels = state.labels ?: return
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val choices = remember(labels) { seatChoices(labels) }
    SeatMap(
        layout = layout,
        labels = labels,
        contentDescription = seatMapDescription(state.hallName, labels),
        screenWidth = state.screenWidth,
        screenDistance = state.screenDistance,
        selectedSeatId = state.selectedSeat?.seatId,
        onSeatClick = onSeatClick,
        onChooseSeatByLabel = { showPicker = true },
        modifier = Modifier.fillMaxWidth(),
    )
    val selected = state.selectedSeat
    Text(
        text = if (selected == null) {
            "Tap a seat to see its row and number."
        } else {
            listOfNotNull(selected.label.description, selected.viewingAngle?.let(::viewingAngleText)).joinToString(" · ")
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (selected == null) CinexTheme.colors.textMuted else CinexTheme.colors.text,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp),
    )
    if (showPicker) {
        SeatPicker(
            choices = choices,
            selectedSeatId = selected?.seatId,
            onSeatSelected = onSeatChosen,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        TextButton(onClick = { showPicker = false }, modifier = Modifier.align(Alignment.End)) { Text("Done") }
    }
}

/** "VISITS" with "Add visit", then the hall's visits, newest first (spec §6.3). */
@Composable
private fun VisitHistory(visits: List<HallVisitRow>, onAddVisit: () -> Unit, onOpenVisit: (Long) -> Unit) {
    Column(
        Modifier.padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Visits", Modifier.weight(1f))
            OutlinedPillButton(text = "Add visit", onClick = onAddVisit)
        }
        RowGroup {
            if (visits.isEmpty()) {
                Text(
                    text = "No visits yet. Log one with \"Add visit\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinexTheme.colors.textMuted,
                    modifier = Modifier.padding(16.dp),
                )
            }
            visits.forEachIndexed { index, visit ->
                if (index > 0) RowDivider()
                VisitHistoryRow(visit, onClick = { onOpenVisit(visit.visitId) })
            }
        }
    }
}

/** Second line of a visit row: "12 Sep 2026 · Row 7 · Seat 10 · OmU"; missing parts are left out. */
internal fun HallVisitRow.detailText(): String = listOfNotNull(
    dateText(date),
    seatLabel?.displayText?.replace(", ", " · "),
    format?.displayName,
).joinToString(" · ")

@Composable
private fun VisitHistoryRow(visit: HallVisitRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = visit.filmTitle ?: "Untitled",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (visit.filmTitle == null) CinexTheme.colors.textMuted else CinexTheme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                visit.detailText(),
                style = MaterialTheme.typography.bodySmall,
                color = CinexTheme.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        visit.seatRating?.let { StarRatingDisplay(it.value) }
    }
}

@Composable
private fun EmptyLayout(title: String, text: String, button: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(CinexIcons.Grid, contentDescription = null, tint = CinexTheme.colors.textMuted, modifier = Modifier.size(28.dp))
        Text(title.uppercase(), style = CinexTheme.textStyles.cardTitle, color = CinexTheme.colors.text, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.textMuted, textAlign = TextAlign.Center)
        Button(onClick = onClick, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(top = 6.dp)) {
            Text(button, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun MessageContent(text: String, onBack: () -> Unit) {
    BackTopBar(onBack)
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = CinexTheme.colors.textMuted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}
