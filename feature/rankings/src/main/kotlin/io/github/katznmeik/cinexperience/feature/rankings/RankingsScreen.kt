package io.github.katznmeik.cinexperience.feature.rankings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RankingsScreen(
        state = state,
        onScopeChange = viewModel::setScope,
        onCategoryChange = viewModel::selectCategory,
        onOpenItem = { id -> if (state.scope == RankingScope.HALLS) onOpenHall(id) else onOpenCinema(id) },
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
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") { Header(state, onScopeChange, onCategoryChange) }
        when {
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
        ChoiceChipRow(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            state.chips.forEachIndexed { index, chip ->
                ChoiceChip(text = chip.label, selected = chip.selected, onClick = { onCategoryChange(index) })
            }
        }
    }
}

/** Full-width two-option segmented control; the selected segment is filled light. */
@Composable
private fun ScopeSwitch(scope: RankingScope, onScopeChange: (RankingScope) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .border(1.dp, CinexTheme.colors.outline, shape)
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = item.position?.toString() ?: NO_SCORE_TEXT,
            style = CinexTheme.textStyles.score,
            color = CinexTheme.colors.textMuted,
            modifier = Modifier.widthIn(min = 24.dp),
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

@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = CinexTheme.colors.textMuted,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}
