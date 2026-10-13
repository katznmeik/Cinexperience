package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Hall detail with the seat map (spec §6.3). Will take a hall id once halls exist (M1). */
@Serializable
data object HallDetailRoute

/** Seat layout editor (spec §6.5). Will take a hall id once halls exist (M1). */
@Serializable
data object LayoutEditorRoute

fun NavController.navigateToHallDetail() = navigate(HallDetailRoute)

fun NavController.navigateToLayoutEditor() = navigate(LayoutEditorRoute)

fun NavGraphBuilder.hallDetailScreen(
    onBack: () -> Unit,
    onEditLayout: () -> Unit,
    onAddVisit: () -> Unit,
) {
    composable<HallDetailRoute> {
        HallDetailScreen(onBack = onBack, onEditLayout = onEditLayout, onAddVisit = onAddVisit)
    }
}

fun NavGraphBuilder.layoutEditorScreen(onBack: () -> Unit) {
    composable<LayoutEditorRoute> {
        LayoutEditorScreen(onBack = onBack)
    }
}
