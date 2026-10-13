package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import kotlinx.serialization.Serializable

/** Hall detail with the seat map (spec §6.3). */
@Serializable
data class HallDetailRoute(val hallId: Long)

/** Seat layout editor (spec §6.5). Will take a hall id when the editor is built (M2). */
@Serializable
data object LayoutEditorRoute

fun NavController.navigateToHallDetail(hallId: Long) = navigate(HallDetailRoute(hallId))

fun NavController.navigateToLayoutEditor() = navigate(LayoutEditorRoute)

fun NavGraphBuilder.hallDetailScreen(
    hallRepository: HallRepository,
    onBack: () -> Unit,
    onEditLayout: () -> Unit,
    onAddVisit: () -> Unit,
) {
    composable<HallDetailRoute> { entry ->
        val hallId = entry.toRoute<HallDetailRoute>().hallId
        HallDetailEntry(
            viewModel = viewModel(factory = HallDetailViewModel.factory(hallId, hallRepository)),
            onBack = dropUnlessResumed(block = onBack),
            onEditLayout = dropUnlessResumed(block = onEditLayout),
            onAddVisit = dropUnlessResumed(block = onAddVisit),
        )
    }
}

fun NavGraphBuilder.layoutEditorScreen(onBack: () -> Unit) {
    composable<LayoutEditorRoute> {
        LayoutEditorScreen(onBack = dropUnlessResumed(block = onBack))
    }
}
