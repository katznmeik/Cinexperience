package io.github.katznmeik.cinexperience.feature.visits.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.model.ShowingTime
import io.github.katznmeik.cinexperience.core.model.UpcomingVisits
import io.github.katznmeik.cinexperience.core.model.VisitListItem
import io.github.katznmeik.cinexperience.core.ui.flow.asResult
import io.github.katznmeik.cinexperience.core.ui.flow.minuteTicks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate

/**
 * @property upcoming the upcoming visits (spec §4.4), soonest first: the Upcoming section.
 * @property visits every other visit, newest first; empty while loading.
 * @property today today in Berlin, for the upcoming visits' "Today" / "Tomorrow".
 */
data class AllVisitsUiState(
    val isLoading: Boolean = true,
    val visits: List<VisitListItem> = emptyList(),
    val errorMessage: String? = null,
    val upcoming: List<VisitListItem> = emptyList(),
    val today: LocalDate? = null,
) {
    /** Every visit, upcoming ones included. */
    val count: Int get() = upcoming.size + visits.size
}

/**
 * All visits across all cinemas (spec §6.8): the upcoming ones first in their own
 * section, soonest first, then the others newest first. Which visits are upcoming
 * changes with time, so the list is split again on every tick (default: every minute).
 */
class AllVisitsViewModel(
    visitRepository: VisitRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    ticks: Flow<Unit> = minuteTicks(),
) : ViewModel() {

    val uiState: StateFlow<AllVisitsUiState> =
        combine(visitRepository.observeAllVisits().asResult(), ticks) { result, _ ->
            result.fold(
                onSuccess = { all ->
                    val now = clock.instant()
                    val upcoming = UpcomingVisits.of(all, now)
                    val upcomingIds = upcoming.mapTo(HashSet()) { it.visit.id }
                    AllVisitsUiState(
                        isLoading = false,
                        visits = all.filter { it.visit.id !in upcomingIds },
                        upcoming = upcoming,
                        today = ShowingTime.today(now),
                    )
                },
                onFailure = { AllVisitsUiState(isLoading = false, errorMessage = LOAD_FAILED) },
            )
        }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AllVisitsUiState())

    companion object {
        const val LOAD_FAILED = "Couldn't load the visits."

        fun factory(visitRepository: VisitRepository, clock: Clock): ViewModelProvider.Factory = viewModelFactory {
            initializer { AllVisitsViewModel(visitRepository, clock) }
        }
    }
}
