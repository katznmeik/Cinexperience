package io.github.katznmeik.cinexperience.feature.rankings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.katznmeik.cinexperience.core.ui.component.CinexScreen

@Composable
internal fun RankingsScreen(modifier: Modifier = Modifier) {
    CinexScreen(
        title = "Rankings",
        subtitle = "Halls and cinemas ranked by overall score or any single category will appear here.",
        modifier = modifier,
    )
}
