package io.github.katznmeik.cinexperience.feature.seatmap.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.model.NO_SCORE_TEXT
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
import io.github.katznmeik.cinexperience.core.seatmapui.windowSeatMapMaxHeight
import io.github.katznmeik.cinexperience.core.ui.component.BackMessageContent
import io.github.katznmeik.cinexperience.core.ui.component.BackTopBar
import io.github.katznmeik.cinexperience.core.ui.component.MessageSnackbarHost
import io.github.katznmeik.cinexperience.core.ui.component.OutlinedPillButton
import io.github.katznmeik.cinexperience.core.ui.component.RowDivider
import io.github.katznmeik.cinexperience.core.ui.component.RowGroup
import io.github.katznmeik.cinexperience.core.ui.component.ScreenTitle
import io.github.katznmeik.cinexperience.core.ui.component.SectionHeader
import io.github.katznmeik.cinexperience.core.ui.component.StarRatingDisplay
import io.github.katznmeik.cinexperience.core.ui.component.isLargeText
import io.github.katznmeik.cinexperience.core.ui.component.rememberMessageSnackbarState
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.text.countText
import io.github.katznmeik.cinexperience.core.ui.text.dateText
import io.github.katznmeik.cinexperience.core.ui.text.isolate
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

/** "ROW 7 · SEAT 10", "ROW 8 · SEAT R", "SEAT 12" (a row without a name): title of the selected-seat card. */
internal fun SelectedSeat.titleText(): String = label.shortText.uppercase()

/**
 * "★ 4.8 seat rating · 3 visits · ≈ 38° viewing angle"; parts without data are
 * left out, wheelchair spaces, companion and sofa places are named first. `null`
 * when nothing is known.
 */
internal fun SelectedSeat.detailText(): String? = listOfNotNull(
    when (label.kind) {
        SeatKind.WHEELCHAIR -> "Wheelchair space"
        SeatKind.COMPANION -> "Companion seat"
        SeatKind.SOFA -> "Sofa place"
        SeatKind.STANDARD -> null
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
    val snackbarHostState = rememberMessageSnackbarState(state.snackbarMessage, onMessageShown)
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
                HallDetailUiState.NotFound -> BackMessageContent("This hall no longer exists.", onBack)
                is HallDetailUiState.Error -> BackMessageContent(state.message, onBack)
            }
        }
        MessageSnackbarHost(snackbarHostState)
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
            .heightIn(min = 48.dp)
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

/** The map's card: narrow side padding, so the seats (and their numbers) get the width. */
@Composable
private fun MapCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CinexTheme.colors.surface)
            .padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 14.dp),
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
        screenOffset = state.screenOffset,
        seatVisuals = state.seatVisuals,
        selectedSeatId = state.selectedSeat?.seatId,
        onSeatClick = onSeatClick,
        onChooseSeatByLabel = { showPicker = true },
        // Tall, narrow halls (e.g. a hall of double sofas) are shown whole, as tall as the window allows.
        maxHeight = windowSeatMapMaxHeight(),
        fitWholeHall = true,
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
                    // No line break right after "★" or "≈" when the line wraps.
                    text = seat.detailText()?.replace("★ ", "★\u00A0")?.replace("≈ ", "≈\u00A0") ?: "No visits in this seat yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinexTheme.colors.textMuted,
                )
            }
            if (seat.isFavourite) {
                Icon(CinexIcons.Star, contentDescription = null, tint = CinexTheme.colors.text, modifier = Modifier.size(22.dp))
            }
        }
        ColourChoice(seat.overrideColour, onSetColour)
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

/** The colour override options: label, colour (`null` = automatic) and fill. */
@Composable
private fun colourOptions(): List<Triple<String, SeatColour?, Color?>> = listOf(
    Triple("Auto", null, null),
    Triple("Great", SeatColour.GREEN, CinexTheme.colors.seat.great),
    Triple("OK", SeatColour.YELLOW, CinexTheme.colors.seat.ok),
    Triple("Avoid", SeatColour.RED, CinexTheme.colors.seat.avoid),
)

/** Screen-reader name of a colour option: "Seat colour: Great", "Seat colour: Auto, from ratings". */
internal fun colourOptionDescription(label: String, colour: SeatColour?): String =
    if (colour == null) "Seat colour: $label, from ratings" else "Seat colour: $label"

/**
 * "Seat colour" caption and the four override buttons (Auto · Great · OK · Avoid),
 * one row normally and two rows of two at large font sizes.
 */
@Composable
private fun ColourChoice(active: SeatColour?, onSetColour: (SeatColour?) -> Unit) {
    val perRow = if (isLargeText()) 2 else 4
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Seat colour",
            style = MaterialTheme.typography.labelMedium,
            color = CinexTheme.colors.textMuted,
            modifier = Modifier.semantics { heading() },
        )
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            colourOptions().chunked(perRow).forEach { options ->
                // Equal heights when one label wraps at a large font size.
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    options.forEach { (label, colour, fill) ->
                        ColourButton(label, colourOptionDescription(label, colour), selected = active == colour, fill = fill) {
                            onSetColour(colour)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One of the colour override buttons: "Auto" is outlined, the colours are filled
 * with their seat colour and dark text. The active one gets a light ring and a
 * check badge in its corner, so it does not rely on colour alone. The label may
 * wrap at large font sizes; the button grows with it.
 */
@Composable
private fun RowScope.ColourButton(text: String, description: String, selected: Boolean, fill: Color?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val inner = RoundedCornerShape(8.dp)
    val ring = CinexTheme.colors.text
    val contentColor = if (fill == null) CinexTheme.colors.text else CinexTheme.colors.onPrimary
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(shape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { contentDescription = description }
            .then(if (selected) Modifier.border(2.dp, ring, shape) else Modifier)
            // The gap between the ring and the fill keeps the ring visible on every colour.
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .fillMaxHeight()
                .clip(inner)
                .then(
                    when {
                        fill != null -> Modifier.background(fill)
                        selected -> Modifier
                        else -> Modifier.border(1.dp, CinexTheme.colors.outline, inner)
                    },
                )
                .padding(horizontal = 6.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor,
                textAlign = TextAlign.Center,
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(ring),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CinexIcons.Check, contentDescription = null, tint = CinexTheme.colors.onPrimary, modifier = Modifier.size(11.dp))
            }
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
        // Columns sized to their widest text at the current font size, so the bars line
        // up in every row and nothing wraps at large font sizes.
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val labelStyle = MaterialTheme.typography.bodyMedium
        val valueStyle = labelStyle.copy(fontWeight = FontWeight.SemiBold)
        val countStyle = MaterialTheme.typography.bodySmall
        val widths = remember(rows, density, labelStyle, countStyle) {
            fun widest(texts: List<String>, style: TextStyle, min: Dp): Dp = with(density) {
                maxOf(min, texts.maxOf { measurer.measure(it, style, softWrap = false).size.width }.toDp())
            }
            ScoreColumnWidths(
                label = widest(rows.map { it.label }, labelStyle, 104.dp),
                value = widest(listOf("0.0", NO_SCORE_TEXT), valueStyle, 28.dp),
                count = widest(rows.map { it.countText() } + "×00", countStyle, 30.dp),
            )
        }
        RowGroup {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                rows.forEach { ScoreRow(it, widths) }
            }
        }
    }
}

private data class ScoreColumnWidths(val label: Dp, val value: Dp, val count: Dp)

/** "×4", or nothing for an unrated category. */
private fun CategoryScoreRow.countText(): String = if (count == 0) "" else "×$count"

@Composable
private fun ScoreRow(row: CategoryScoreRow, widths: ScoreColumnWidths) {
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
            softWrap = false,
            modifier = Modifier.width(widths.label),
        )
        ScoreBar(fraction = ((row.score ?: 0.0) / 5.0).toFloat(), modifier = Modifier.weight(1f))
        Text(
            formatScore(row.score),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (row.score == null) CinexTheme.colors.textMuted else CinexTheme.colors.text,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(widths.value),
        )
        Text(
            row.countText(),
            style = MaterialTheme.typography.bodySmall,
            color = CinexTheme.colors.textMuted,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(widths.count),
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
    seatLabel?.shortText,
    format?.displayName,
).joinToString(" · ")

@Composable
private fun VisitHistoryRow(visit: HallVisitRow, onClick: () -> Unit) {
    // The stars grow with the text: with large text they go under it, so the texts keep the width.
    val starsBelow = isLargeText()
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
                text = visit.filmTitle?.let(::isolate) ?: "Untitled",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (visit.filmTitle == null) CinexTheme.colors.textMuted else CinexTheme.colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                visit.detailText(),
                style = MaterialTheme.typography.bodySmall,
                color = CinexTheme.colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (starsBelow) visit.seatRating?.let { StarRatingDisplay(it.value, Modifier.padding(top = 2.dp)) }
        }
        if (!starsBelow) visit.seatRating?.let { StarRatingDisplay(it.value) }
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
