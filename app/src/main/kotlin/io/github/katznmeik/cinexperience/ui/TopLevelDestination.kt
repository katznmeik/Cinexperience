package io.github.katznmeik.cinexperience.ui

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.feature.backup.SettingsRoute
import io.github.katznmeik.cinexperience.feature.cinemas.CinemasRoute
import io.github.katznmeik.cinexperience.feature.rankings.RankingsRoute

/** The three bottom-navigation tabs (spec §6). */
enum class TopLevelDestination(
    val label: String,
    val icon: ImageVector,
    val route: Any,
    val testTag: String,
) {
    Cinemas("Cinemas", CinexIcons.Cinemas, CinemasRoute, "tab_cinemas"),
    Rankings("Rankings", CinexIcons.Rankings, RankingsRoute, "tab_rankings"),
    Settings("Settings", CinexIcons.Settings, SettingsRoute, "tab_settings"),
}
