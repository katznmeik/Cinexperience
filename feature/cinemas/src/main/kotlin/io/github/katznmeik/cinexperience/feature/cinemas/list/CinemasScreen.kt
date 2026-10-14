package io.github.katznmeik.cinexperience.feature.cinemas.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    message: String?,
    onMessageShown: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CinemasScreen(
        state,
        onSortChange = viewModel::setSort,
        onShowFavourites = viewModel::showFavourites,
        onOpenCinema = onOpenCinema,
        onAddVisit = onAddVisit,
        message = message,
        onMessageShown = onMessageShown,
    )
}

/** Cinema list (spec §6.1): sort and Favourites chips, one card per cinema, "Add visit" button. */
@Composable
internal fun CinemasScreen(
    state: CinemasUiState,
    onSortChange: (CinemaSort) -> Unit,
    onOpenCinema: (Long) -> Unit,
    onAddVisit: () -> Unit,
    modifier: Modifier = Modifier,
    onShowFavourites: () -> Unit = {},
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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Room for the floating button below the last card.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") { Header(state, onSortChange, onShowFavourites) }
            when {
                state.errorMessage != null -> item(key = "error") { InfoText(state.errorMessage) }
                state.showsNoFavourites -> item(key = "no-favourites") { InfoText(NO_FAVOURITES_TEXT) }
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
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

/** Empty state of the Favourites chip. */
internal const val NO_FAVOURITES_TEXT = "No favourite cinemas yet. Mark one with ☆ on its page."

@Composable
private fun Header(state: CinemasUiState, onSortChange: (CinemaSort) -> Unit, onShowFavourites: () -> Unit) {
    Column(
        // The chips' 48 dp touch targets add 6 dp above and below their 36 dp pills.
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Title left, counts right; with large text the counts wrap below the title.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ScreenTitle("Cinemas", Modifier.alignByBaseline().padding(end = 12.dp))
            if (!state.isLoading && state.errorMessage == null) {
                Text(
                    text = "${countText(state.venueCount, "venue")} · ${countText(state.totalVisits, "visit")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinexTheme.colors.textMuted,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        // Three sorts and the Favourites filter: exactly one chip is selected.
        ChoiceChipRow {
            val all = state.filter == CinemaFilter.ALL
            CinemaSort.entries.forEach { sort ->
                ChoiceChip(text = sort.label, selected = all && sort == state.sort, onClick = { onSortChange(sort) })
            }
            ChoiceChip(text = "Favourites", selected = !all, onClick = onShowFavourites, icon = CinexIcons.Star)
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = summary.cinema.name.uppercase(),
                    style = CinexTheme.textStyles.cardTitle,
                    color = CinexTheme.colors.text,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .alignByBaseline(),
                )
                // Small star marker sitting on the first line's baseline and growing with
                // the font size; the card merges its description into its announcement.
                if (summary.cinema.isFavourite) {
                    Icon(
                        CinexIcons.Star,
                        contentDescription = "Favourite",
                        tint = CinexTheme.colors.text,
                        modifier = Modifier
                            .size(with(LocalDensity.current) { 14.sp.toDp() })
                            .alignBy { it.measuredHeight - it.measuredHeight / 10 },
                    )
                }
            }
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
