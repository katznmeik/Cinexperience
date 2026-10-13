package io.github.katznmeik.cinexperience.feature.visits

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.katznmeik.cinexperience.core.ui.component.CinexScreen

@Composable
internal fun VisitEditScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CinexScreen(
        title = "Add visit",
        subtitle = "The visit form — hall, date, film, seat, ratings, note and photo — will appear here.",
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun AllVisitsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CinexScreen(
        title = "All visits",
        subtitle = "Every visit across all cinemas, newest first, will appear here.",
        onBack = onBack,
        modifier = modifier,
    )
}
