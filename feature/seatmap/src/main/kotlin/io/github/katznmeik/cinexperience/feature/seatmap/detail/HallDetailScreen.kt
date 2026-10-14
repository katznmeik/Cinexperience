package io.github.katznmeik.cinexperience.feature.seatmap.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatKind
import io.github.katznmeik.cinexperience.core.model.formatScore
import io.github.katznmeik.cinexperience.core.seatmapui.SeatLegend
import io.github.katznmeik.cinexperience.core.seatmapui.SeatMap
import io.github.katznmeik.cinexperience.core.seatmapui.SeatPicker
import io.github.katznmeik.cinexperience.core.seatmapui.seatChoices
import io.github.katznmeik.cinexperience.core.seatmapui.seatDescription
import io.github.katznmeik.cinexperience.core.seatmapui.seatMapDescription
import io.github.katznmeik.cinexperience.core.seatmapui.seatStatesDescription
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
        onSetSeatColour = viewModel::setSeatColour,
        onToggleFavourite = viewModel::toggleFavourite,
        onMessageShown = viewModel::messageShown,
    )
}

/** "delphi LUX · 183 seats · 4 visits"; without a layout "delphi LUX · no visits". */
internal fun hallMetaText(state: HallDetailUiState.Loaded): String = listOfNotNull(
    state.cinemaName,
    state.layout?.let { countText(it.seatCount, "seat") },
    if (state.visitCount == 0) "no visits" else countText(state.visitCount, "visit"),
).joinToString(" · ")

/** "ROW 7 · SEAT 10": title of the selected-seat card. */
internal fun SelectedSeat.titleText(): String = label.displayText.replace(", ", " · ").uppercase()

/**
 * "★ 4.8 seat rating · 3 visits · ≈ 38° viewing angle"; parts without data are
 * left out, companion and sofa places are named first. `null` when nothing is known.
 */
internal fun SelectedSeat.detailText(): String? = listOfNotNull(
    when (label.kind) {
        SeatKind.COMPANION -> "Companion seat"
        SeatKind.SOFA -> "Sofa place"
        else -> null
    },
    averageRating?.let { "★ ${formatScore(it)} seat rating" },
    visitCount.takeIf { it > 0 }?.let { countText(it, "visit") },
    viewingAngle?.let(::viewingAngleText),
).joinToString(" · ").ifEmpty { null }

/** "Sound: 4.3 from 4 ratings" / "Sound: not rated", for screen readers. */
internal fun CategoryScoreRow.description(): String =
    if (score == null) "$label: not rated" else "$label: ${formatScore(score)} from ${countText(count, "rating")}"

/** Hall detail (spec §6.3). */
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
    onSetSeatColour: (SeatId, SeatColour?) -> Unit = { _, _ -> },
    onToggleFavourite: (SeatId) -> Unit = {},
    onMessageShown: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = (state as? HallDetailUiState.Loaded)?.message
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            onMessageShown()
        }
    }
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            when (state) {
                is HallDetailUiState.Loaded -> LoadedContent(
                    state = state,
                    onBack = onBack,
                    onEditLayout = onEditLayout,
                    onAddVisit = onAddVisit,
                    onOpenVisit = onOpenVisit,
                    onSeatClick = onSeatClick,
                    onSeatChosen = onSeatChosen,
                    onSetSeatColour = onSetSeatColour,
                    onToggleFavourite = onToggleFavourite,
                )
                HallDetailUiState.Loading -> BackTopBar(onBack)
                HallDetailUiState.NotFound -> MessageContent("This hall no longer exists.", onBack)
                is HallDetailUiState.Error -> MessageContent(state.message, onBack)
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ColumnScope.LoadedContent(
    state: HallDetailUiState.Loaded,
    onBack: () -> Unit,
    onEditLayout: (Long) -> Unit,
    onAddVisit: (Long) -> Unit,
    onOpenVisit: (Long) -> Unit,
    onSeatClick: (SeatId) -> Unit,
    onSeatChosen: (SeatId) -> Unit,
    onSetSeatColour: (SeatId, SeatColour?) -> Unit,
    onToggleFavourite: (SeatId) -> Unit,
) {
    BackTopBar(onBack) {
        if (state.layout != null) EditLayoutAction(onClick = { onEditLayout(state.hallId) })
    }
    Header(state.hallName, hallMetaText(state), state.overallScore)
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
    state.selectedSeat?.let { seat ->
        SelectedSeatCard(
            seat = seat,
            onSetColour = { onSetSeatColour(seat.seatId, it) },
            onToggleFavourite = { onToggleFavourite(seat.seatId) },
        )
    }
    ScoreBreakdown(state.scores)
    VisitHistory(
        visits = state.visits,
        onAddVisit = { onAddVisit(state.hallId) },
        onOpenVisit = onOpenVisit,
    )
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

/** Title and meta on the left, the big overall score (or "–") on the right. */
@Composable
private fun Header(title: String, meta: String, overallScore: Double?) {
    Row(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ScreenTitle(title)
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.textMuted)
        }
        Column(
            modifier = Modifier
                .widthIn(min = 64.dp)
                .clearAndSetSemantics {
                    contentDescription = overallScore?.let { "Overall score ${formatScore(it)}" } ?: "No overall score yet"
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(formatScore(overallScore), style = CinexTheme.textStyles.scoreLarge, color = CinexTheme.colors.text)
            Text(
                "overall",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = CinexTheme.colors.textMuted,
            )
        }
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
    val description = remember(state.hallName, labels, state.seatVisuals) {
        "${seatMapDescription(state.hallName, labels)} ${seatStatesDescription(state.seatVisuals, labels)}".trim()
    }
    SeatMap(
        layout = layout,
        labels = labels,
        contentDescription = description,
        screenWidth = state.screenWidth,
        screenDistance = state.screenDistance,
        seatVisuals = state.seatVisuals,
        selectedSeatId = state.selectedSeat?.seatId,
        onSeatClick = onSeatClick,
        onChooseSeatByLabel = { showPicker = true },
        modifier = Modifier.fillMaxWidth(),
    )
    SeatLegend(Modifier.padding(top = 14.dp))
    if (state.selectedSeat == null) {
        Text(
            text = "Tap a seat for its rating, colour and favourite.",
            style = MaterialTheme.typography.bodySmall,
            color = CinexTheme.colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    if (showPicker) {
        SeatPicker(
            choices = choices,
            selectedSeatId = state.selectedSeat?.seatId,
            onSeatSelected = onSeatChosen,
            seatVisuals = state.seatVisuals,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        TextButton(onClick = { showPicker = false }, modifier = Modifier.align(Alignment.End)) { Text("Done") }
    }
}

/**
 * The selected seat (spec §6.3, shown inline under the map): label, rating,
 * visits and viewing angle, the colour override (Auto · Great · OK · Avoid) and
 * the favourite toggle.
 */
@Composable
private fun SelectedSeatCard(seat: SelectedSeat, onSetColour: (SeatColour?) -> Unit, onToggleFavourite: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(CinexTheme.colors.surfaceRaised)
            .border(1.dp, CinexTheme.colors.outline, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = seat.titleText(),
                    style = CinexTheme.textStyles.cardTitle,
                    color = CinexTheme.colors.text,
                    modifier = Modifier.clearAndSetSemantics {
                        heading()
                        contentDescription = seatDescription(seat.label, seat.visual)
                    },
                )
                Text(
                    text = seat.detailText() ?: "No visits in this seat yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinexTheme.colors.textMuted,
                )
            }
            if (seat.isFavourite) {
                Icon(CinexIcons.Star, contentDescription = null, tint = CinexTheme.colors.text, modifier = Modifier.size(22.dp))
            }
        }
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColourButton("Auto", selected = seat.overrideColour == null, fill = null, onClick = { onSetColour(null) })
            ColourButton("Great", seat.overrideColour == SeatColour.GREEN, CinexTheme.colors.seat.great) { onSetColour(SeatColour.GREEN) }
            ColourButton("OK", seat.overrideColour == SeatColour.YELLOW, CinexTheme.colors.seat.ok) { onSetColour(SeatColour.YELLOW) }
            ColourButton("Avoid", seat.overrideColour == SeatColour.RED, CinexTheme.colors.seat.avoid) { onSetColour(SeatColour.RED) }
        }
        OutlinedButton(
            onClick = onToggleFavourite,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, CinexTheme.colors.outline),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            Icon(
                imageVector = if (seat.isFavourite) CinexIcons.Star else CinexIcons.StarOutline,
                contentDescription = null,
                tint = CinexTheme.colors.text,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (seat.isFavourite) "Remove favourite" else "Mark as favourite",
                style = MaterialTheme.typography.labelLarge,
                color = CinexTheme.colors.text,
            )
        }
    }
}

/**
 * One of the colour override buttons: "Auto" is outlined, the colours are filled
 * with their seat colour and dark text. The active one gets a light ring and a
 * check mark, so it does not rely on colour alone.
 */
@Composable
private fun RowScope.ColourButton(text: String, selected: Boolean, fill: Color?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val ring = CinexTheme.colors.text
    val contentColor = if (fill == null) CinexTheme.colors.text else CinexTheme.colors.onPrimary
    Box(
        modifier = Modifier
            .weight(1f)
            .minimumInteractiveComponentSize()
            .height(46.dp)
            .clip(shape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .then(if (selected) Modifier.border(2.dp, ring, shape) else Modifier)
            // The gap between the ring and the fill keeps the ring visible on every colour.
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        val inner = RoundedCornerShape(8.dp)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(inner)
                .then(
                    when {
                        fill != null -> Modifier.background(fill)
                        selected -> Modifier
                        else -> Modifier.border(1.dp, CinexTheme.colors.outline, inner)
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (selected) {
                Icon(CinexIcons.Check, contentDescription = null, tint = contentColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

/** "SCORES": the six hall categories with a bar, value and rating count (spec §6.3). */
@Composable
private fun ScoreBreakdown(rows: List<CategoryScoreRow>) {
    Column(
        Modifier.padding(start = 12.dp, end = 12.dp, top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader("Scores", Modifier.padding(vertical = 6.dp))
        RowGroup {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                rows.forEach { ScoreRow(it) }
            }
        }
    }
}

@Composable
private fun ScoreRow(row: CategoryScoreRow) {
    val description = row.description()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            row.label,
            style = MaterialTheme.typography.bodyMedium,
            color = CinexTheme.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(104.dp),
        )
        ScoreBar(fraction = ((row.score ?: 0.0) / 5.0).toFloat(), modifier = Modifier.weight(1f))
        Text(
            formatScore(row.score),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (row.score == null) CinexTheme.colors.textMuted else CinexTheme.colors.text,
            textAlign = TextAlign.End,
            modifier = Modifier.width(28.dp),
        )
        Text(
            if (row.count == 0) "" else "×${row.count}",
            style = MaterialTheme.typography.bodySmall,
            color = CinexTheme.colors.textMuted,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(30.dp),
        )
    }
}

/** Thin bar: a light fill on the divider track, [fraction] of the width. */
@Composable
private fun ScoreBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(CinexTheme.colors.divider),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(CinexTheme.colors.text),
            )
        }
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
