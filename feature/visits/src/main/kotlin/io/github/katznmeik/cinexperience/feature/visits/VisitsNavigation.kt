package io.github.katznmeik.cinexperience.feature.visits

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.katznmeik.cinexperience.core.data.files.VisitPhotoStore
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.ui.navigation.dropUnlessResumedWith
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditEntry
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditMode
import io.github.katznmeik.cinexperience.feature.visits.edit.VisitEditViewModel
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsEntry
import io.github.katznmeik.cinexperience.feature.visits.list.AllVisitsViewModel
import kotlinx.serialization.Serializable

/**
 * Add a visit (spec §6.4). [hallId] pre-selects the hall (opened from a hall);
 * `null` lets the user pick cinema and hall (Cinemas tab).
 */
@Serializable
data class AddVisitRoute(val hallId: Long? = null)

/** Edit (or delete) an existing visit (spec §6.4). */
@Serializable
data class EditVisitRoute(val visitId: Long)

/** Chronological list of all visits (spec §6.8), reached from Settings. */
@Serializable
data object AllVisitsRoute

fun NavController.navigateToAddVisit(hallId: Long? = null) = navigate(AddVisitRoute(hallId))

fun NavController.navigateToEditVisit(visitId: Long) = navigate(EditVisitRoute(visitId))

fun NavController.navigateToAllVisits() = navigate(AllVisitsRoute)

/**
 * The add and edit forms. They close themselves through [onClose] once saved,
 * discarded or deleted (closing with unsaved changes asks first). [onClose] is
 * called once, from the form's state, so it is not limited to the resumed state.
 */
fun NavGraphBuilder.visitEditScreens(
    cinemaRepository: CinemaRepository,
    visitRepository: VisitRepository,
    visitPhotoStore: VisitPhotoStore,
    onClose: () -> Unit,
) {
    composable<AddVisitRoute> { entry ->
        val hallId = entry.toRoute<AddVisitRoute>().hallId
        VisitEditEntry(
            viewModel = viewModel(
                factory = VisitEditViewModel.factory(VisitEditMode.Add(hallId), cinemaRepository, visitRepository, visitPhotoStore),
            ),
            photoStore = visitPhotoStore,
            onClose = onClose,
        )
    }
    composable<EditVisitRoute> { entry ->
        val visitId = entry.toRoute<EditVisitRoute>().visitId
        VisitEditEntry(
            viewModel = viewModel(
                factory = VisitEditViewModel.factory(VisitEditMode.Edit(visitId), cinemaRepository, visitRepository, visitPhotoStore),
            ),
            photoStore = visitPhotoStore,
            onClose = onClose,
        )
    }
}

fun NavGraphBuilder.allVisitsScreen(
    visitRepository: VisitRepository,
    onBack: () -> Unit,
    onOpenVisit: (visitId: Long) -> Unit,
) {
    composable<AllVisitsRoute> {
        AllVisitsEntry(
            viewModel = viewModel(factory = AllVisitsViewModel.factory(visitRepository)),
            onBack = dropUnlessResumed(block = onBack),
            onOpenVisit = dropUnlessResumedWith(onOpenVisit),
        )
    }
}
