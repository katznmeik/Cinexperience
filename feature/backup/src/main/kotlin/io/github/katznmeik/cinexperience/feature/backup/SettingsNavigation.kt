package io.github.katznmeik.cinexperience.feature.backup

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Settings tab (spec §6.7): backup, data management and about. */
@Serializable
data object SettingsRoute

fun NavGraphBuilder.settingsScreen(onOpenAllVisits: () -> Unit) {
    composable<SettingsRoute> {
        SettingsScreen(onOpenAllVisits = onOpenAllVisits)
    }
}
