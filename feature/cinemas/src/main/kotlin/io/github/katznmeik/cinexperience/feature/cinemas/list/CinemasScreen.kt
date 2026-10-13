package io.github.katznmeik.cinexperience.feature.cinemas.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.model.CinemaSummary
import io.github.katznmeik.cinexperience.core.model.formatScore
import io.github.katznmeik.cinexperience.core.ui.component.ChoiceChip
import io.github.katznmeik.cinexperience.core.ui.component.ChoiceChipRow
import io.github.katznmeik.cinexperience.core.ui.component.PrimaryActionButton
import io.github.katznmeik.cinexperience.core.ui.component.ScreenTitle
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.text.countText
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.cinemas.cinemaMeta
import io.github.katznmeik.cinexperience.feature.cinemas.visitsText

@Composable
internal fun CinemasEntry(
    viewModel: CinemasViewModel,
    onOpenCinema: (Long) -> Unit,
    onAddVisit: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CinemasScreen(state, onSortChange = viewModel::setSort, onOpenCinema = onOpenCinema, onAddVisit = onAddVisit)
}

/** Cinema list (spec §6.1): sort chips, one card per cinema, "Add visit" button. */
@Composable
internal fun CinemasScreen(
    state: CinemasUiState,
    onSortChange: (CinemaSort) -> Unit,
    onOpenCinema: (Long) -> Unit,
    onAddVisit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Room for the floating button below the last card.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") { Header(state, onSortChange) }
            when {
                state.errorMessage != null -> item(key = "error") { InfoText(state.errorMessage) }
                !state.isLoading && state.cinemas.isEmpty() ->
                    item(key = "empty") { InfoText("No cinemas yet. Add one in Settings → Manage cinemas & halls.") }
            }
            items(state.cinemas, key = { it.cinema.id }) { summary ->
                CinemaCard(summary, onClick = { onOpenCinema(summary.cinema.id) })
            }
        }
        PrimaryActionButton(
            text = "Add visit",
            icon = CinexIcons.Add,
            onClick = onAddVisit,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        )
    }
}

@Composable
private fun Header(state: CinemasUiState, onSortChange: (CinemaSort) -> Unit) {
    Column(
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 28.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            ScreenTitle("Cinemas", Modifier.alignByBaseline())
            Spacer(Modifier.weight(1f))
            if (!state.isLoading) {
                Text(
                    text = "${countText(state.cinemas.size, "venue")} · ${countText(state.totalVisits, "visit")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinexTheme.colors.textMuted,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        ChoiceChipRow {
            CinemaSort.entries.forEach { sort ->
                ChoiceChip(text = sort.label, selected = sort == state.sort, onClick = { onSortChange(sort) })
            }
        }
    }
}

@Composable
private fun CinemaCard(summary: CinemaSummary, onClick: () -> Unit) {
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
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = summary.cinema.name.uppercase(),
                style = CinexTheme.textStyles.cardTitle,
                color = CinexTheme.colors.text,
            )
            Text(
                text = cinemaMeta(summary.cinema, summary.hallCount),
                style = MaterialTheme.typography.bodySmall,
                color = CinexTheme.colors.textMuted,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = formatScore(summary.overallScore),
                style = CinexTheme.textStyles.score,
                color = CinexTheme.colors.text,
            )
            Text(
                text = visitsText(summary.visitCount),
                style = MaterialTheme.typography.labelSmall,
                color = CinexTheme.colors.textMuted,
            )
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
