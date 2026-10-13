package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.katznmeik.cinexperience.core.ui.component.CinexScreen
import io.github.katznmeik.cinexperience.core.ui.component.NavigationRow
import io.github.katznmeik.cinexperience.core.ui.component.RowDivider
import io.github.katznmeik.cinexperience.core.ui.component.RowGroup

@Composable
internal fun HallDetailScreen(
    onBack: () -> Unit,
    onEditLayout: () -> Unit,
    onAddVisit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CinexScreen(
        title = "Hall",
        subtitle = "The colour-coded seat map, score breakdown and visit history will appear here.",
        onBack = onBack,
        modifier = modifier,
    ) {
        RowGroup {
            NavigationRow(title = "Edit layout", subtitle = "Placeholder link", onClick = onEditLayout)
            RowDivider()
            NavigationRow(title = "Add visit", subtitle = "Placeholder link", onClick = onAddVisit)
        }
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
