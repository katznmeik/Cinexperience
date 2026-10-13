package io.github.katznmeik.cinexperience.feature.rankings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Rankings tab (spec §6.6). */
@Serializable
data object RankingsRoute

fun NavGraphBuilder.rankingsScreen() {
    composable<RankingsRoute> {
        RankingsScreen()
    }
}
