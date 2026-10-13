package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.ui.component.CinexScreen
import io.github.katznmeik.cinexperience.core.ui.component.NavigationRow
import io.github.katznmeik.cinexperience.core.ui.component.RowDivider
import io.github.katznmeik.cinexperience.core.ui.component.RowGroup
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

@Composable
internal fun HallDetailEntry(
    viewModel: HallDetailViewModel,
    onBack: () -> Unit,
    onEditLayout: () -> Unit,
    onAddVisit: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HallDetailScreen(state, onBack, onEditLayout, onAddVisit)
}

/** Hall detail placeholder (spec §6.3): real hall and cinema name; the seat map follows in M4. */
@Composable
internal fun HallDetailScreen(
    state: HallDetailUiState,
    onBack: () -> Unit,
    onEditLayout: () -> Unit,
    onAddVisit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is HallDetailUiState.Loaded -> CinexScreen(
            title = state.hallName,
            subtitle = state.cinemaName,
            onBack = onBack,
            modifier = modifier,
        ) {
            Text(
                text = "Seat map coming in M4",
                style = MaterialTheme.typography.bodyMedium,
                color = CinexTheme.colors.textMuted,
            )
            RowGroup {
                NavigationRow(title = "Edit layout", subtitle = "Placeholder link", onClick = onEditLayout)
                RowDivider()
                NavigationRow(title = "Add visit", subtitle = "Placeholder link", onClick = onAddVisit)
            }
        }
        HallDetailUiState.Loading -> CinexScreen(title = "", onBack = onBack, modifier = modifier)
        HallDetailUiState.NotFound ->
            CinexScreen(title = "Hall", subtitle = "This hall no longer exists.", onBack = onBack, modifier = modifier)
        is HallDetailUiState.Error -> CinexScreen(title = "Hall", subtitle = state.message, onBack = onBack, modifier = modifier)
    }
}

@Composable
internal fun LayoutEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CinexScreen(
        title = "Layout editor",
        subtitle = "The seat grid editor with screen position and reference screenshot will appear here.",
        onBack = onBack,
        modifier = modifier,
    )
}
