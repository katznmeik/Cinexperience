package io.github.katznmeik.cinexperience.feature.visits

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Add / edit visit form (spec §6.4). Will take hall / visit ids once they exist. */
@Serializable
data object VisitEditRoute

/** Chronological list of all visits (spec §6.8), reached from Settings. */
@Serializable
data object AllVisitsRoute

fun NavController.navigateToVisitEdit() = navigate(VisitEditRoute)

fun NavController.navigateToAllVisits() = navigate(AllVisitsRoute)

fun NavGraphBuilder.visitEditScreen(onBack: () -> Unit) {
    composable<VisitEditRoute> {
        VisitEditScreen(onBack = onBack)
    }
}

fun NavGraphBuilder.allVisitsScreen(onBack: () -> Unit) {
    composable<AllVisitsRoute> {
        AllVisitsScreen(onBack = onBack)
    }
}
