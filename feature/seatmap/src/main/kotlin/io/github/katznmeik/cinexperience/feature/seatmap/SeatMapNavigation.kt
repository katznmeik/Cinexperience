package io.github.katznmeik.cinexperience.feature.seatmap

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.ui.navigation.dropUnlessResumedWith
import io.github.katznmeik.cinexperience.feature.seatmap.detail.HallDetailEntry
import io.github.katznmeik.cinexperience.feature.seatmap.detail.HallDetailViewModel
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorEntry
import io.github.katznmeik.cinexperience.feature.seatmap.editor.LayoutEditorViewModel
import kotlinx.serialization.Serializable

/** Hall detail with the seat map (spec §6.3). */
@Serializable
data class HallDetailRoute(val hallId: Long)

/** Seat layout editor of one hall (spec §6.5). */
@Serializable
data class LayoutEditorRoute(val hallId: Long)

fun NavController.navigateToHallDetail(hallId: Long) = navigate(HallDetailRoute(hallId))

fun NavController.navigateToLayoutEditor(hallId: Long) = navigate(LayoutEditorRoute(hallId))

fun NavGraphBuilder.hallDetailScreen(
    hallRepository: HallRepository,
    onBack: () -> Unit,
    onEditLayout: (hallId: Long) -> Unit,
    onAddVisit: () -> Unit,
) {
    composable<HallDetailRoute> { entry ->
        val hallId = entry.toRoute<HallDetailRoute>().hallId
        HallDetailEntry(
            viewModel = viewModel(factory = HallDetailViewModel.factory(hallId, hallRepository)),
            onBack = dropUnlessResumed(block = onBack),
            onEditLayout = dropUnlessResumedWith(onEditLayout),
            onAddVisit = dropUnlessResumed(block = onAddVisit),
        )
    }
}

/**
 * The editor closes itself through [onClose] once it has saved or discarded (Back
 * and "Discard" ask first when there are unsaved changes). [onClose] is called
 * once, from the editor's state, so it is not limited to the resumed state.
 */
fun NavGraphBuilder.layoutEditorScreen(
    hallRepository: HallRepository,
    referenceImageStore: ReferenceImageStore,
    onClose: () -> Unit,
) {
    composable<LayoutEditorRoute> { entry ->
        val hallId = entry.toRoute<LayoutEditorRoute>().hallId
        LayoutEditorEntry(
            viewModel = viewModel(factory = LayoutEditorViewModel.factory(hallId, hallRepository, referenceImageStore)),
            referenceImages = referenceImageStore,
            onClose = onClose,
        )
    }
}
