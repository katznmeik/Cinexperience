package io.github.katznmeik.cinexperience.feature.rankings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.model.NO_SCORE_TEXT
import io.github.katznmeik.cinexperience.core.model.formatScore
import io.github.katznmeik.cinexperience.core.ui.component.ChoiceChip
import io.github.katznmeik.cinexperience.core.ui.component.ChoiceChipRow
import io.github.katznmeik.cinexperience.core.ui.component.ScreenTitle
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

@Composable
internal fun RankingsEntry(
    viewModel: RankingsViewModel,
    onOpenHall: (Long) -> Unit,
    onOpenCinema: (Long) -> Unit,
    message: String?,
    onMessageShown: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RankingsScreen(
        state = state,
        onScopeChange = viewModel::setScope,
        onCategoryChange = viewModel::selectCategory,
        onOpenItem = { id -> if (state.scope == RankingScope.HALLS) onOpenHall(id) else onOpenCinema(id) },
        message = message,
        onMessageShown = onMessageShown,
    )
}

/** Rankings tab (spec §6.6): scope switch, category chips, ranked list. */
@Composable
internal fun RankingsScreen(
    state: RankingsUiState,
    onScopeChange: (RankingScope) -> Unit,
    onCategoryChange: (index: Int) -> Unit,
    onOpenItem: (Long) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    onMessageShown: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            onMessageShown()
        }
    }
    Box(modifier.fillMaxSize()) {
        RankingsList(state, onScopeChange, onCategoryChange, onOpenItem)
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun RankingsList(
    state: RankingsUiState,
    onScopeChange: (RankingScope) -> Unit,
    onCategoryChange: (index: Int) -> Unit,
    onOpenItem: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") { Header(state, onScopeChange, onCategoryChange) }
        when {
            // Nothing below the header until the first data arrives, so no empty-state flash.
            state.isLoading -> Unit
            state.errorMessage != null -> item(key = "error") { InfoText(state.errorMessage) }
            state.nothingRated -> item(key = "empty") {
                InfoText("Nothing rated yet. Log a visit with ratings and your halls and cinemas are ranked here.")
            }
            else -> items(state.items, key = { "${state.scope}-${it.id}" }) { item ->
                RankedCard(item, onClick = { onOpenItem(item.id) })
            }
        }
    }
}

@Composable
private fun Header(
    state: RankingsUiState,
    onScopeChange: (RankingScope) -> Unit,
    onCategoryChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 28.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Rankings", Modifier.padding(horizontal = 4.dp))
        ScopeSwitch(state.scope, onScopeChange)
        ChoiceChipRow(Modifier.padding(horizontal = 4.dp)) {
            state.chips.forEachIndexed { index, chip ->
                ChoiceChip(text = chip.label, selected = chip.selected, onClick = { onCategoryChange(index) })
            }
        }
    }
}

/**
 * Full-width two-option segmented control on a filled surface track; the selected
 * segment is filled light. Segments are 48 dp tall (touch target).
 */
@Composable
private fun ScopeSwitch(scope: RankingScope, onScopeChange: (RankingScope) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // At least 54 dp; taller when a label wraps (large text), both segments alike.
            .heightIn(min = 54.dp)
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(CinexTheme.colors.surface)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        RankingScope.entries.forEach { option ->
            val selected = option == scope
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(9.dp))
                    .then(if (selected) Modifier.background(CinexTheme.colors.text) else Modifier)
                    .selectable(selected = selected, onClick = { onScopeChange(option) }, role = Role.Tab),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (selected) CinexTheme.colors.onPrimary else CinexTheme.colors.text,
                )
            }
        }
    }
}

@Composable
private fun RankedCard(item: RankedItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CinexTheme.colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = item.accessibilityText()
                role = Role.Button
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = item.position?.toString() ?: NO_SCORE_TEXT,
            style = CinexTheme.textStyles.score,
            color = CinexTheme.colors.textMuted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            // Wide enough for two digits at any text size, so the titles stay aligned.
            modifier = Modifier.width(rankWidth()),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = item.title.uppercase(),
                style = CinexTheme.textStyles.cardTitle,
                color = CinexTheme.colors.text,
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CinexTheme.colors.textMuted,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = formatScore(item.score),
                style = CinexTheme.textStyles.score,
                color = if (item.score == null) CinexTheme.colors.textMuted else CinexTheme.colors.text,
            )
            if (item.score == null) {
                Text(
                    text = "not rated",
                    style = MaterialTheme.typography.labelSmall,
                    color = CinexTheme.colors.textMuted,
                )
            }
        }
    }
}

/** Width of the position column: two digits in the score style, at least 32 dp. */
@Composable
private fun rankWidth(): Dp {
    val measurer = rememberTextMeasurer()
    val style = CinexTheme.textStyles.score
    val density = LocalDensity.current
    return remember(style, density) {
        with(density) { measurer.measure("88", style).size.width.toDp() }.coerceAtLeast(32.dp)
    }
}

/** Spoken summary, e.g. "Rank 1, International, 1 hall · 3 visits, score 4.4" or "Capitol Dahlem, not rated". */
internal fun RankedItem.accessibilityText(): String = if (position == null || score == null) {
    "$title, ${subtitle.replace(" · ", ", ")}, not rated"
} else {
    "Rank $position, $title, ${subtitle.replace(" · ", ", ")}, score ${formatScore(score)}"
}

@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = CinexTheme.colors.textMuted,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}
