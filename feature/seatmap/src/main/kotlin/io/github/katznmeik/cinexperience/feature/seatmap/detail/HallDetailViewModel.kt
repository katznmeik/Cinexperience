package io.github.katznmeik.cinexperience.feature.seatmap.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.model.HallDetail
import io.github.katznmeik.cinexperience.core.model.LayoutLabels
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.ViewingAngle
import io.github.katznmeik.cinexperience.core.model.viewingAngle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/** One row of the hall's visit history (spec §6.3). */
data class HallVisitRow(
    val visitId: Long,
    val filmTitle: String?,
    val date: LocalDate,
    /** Label of the visit's seat in the current layout; `null` without seat or if it was removed. */
    val seatLabel: SeatLabel?,
    val format: ScreeningFormat?,
    val seatRating: Rating?,
)

/** The seat tapped on the hall's map. */
data class SelectedSeat(val seatId: SeatId, val label: SeatLabel, val viewingAngle: ViewingAngle?)

sealed interface HallDetailUiState {
    data object Loading : HallDetailUiState
    data object NotFound : HallDetailUiState
    data class Error(val message: String) : HallDetailUiState

    /**
     * @property layout `null` when the hall has no layout yet or it can't be read
     *   ([layoutUnreadable]).
     */
    data class Loaded(
        val hallId: Long,
        val hallName: String,
        val cinemaName: String,
        val layout: SeatLayout?,
        val labels: LayoutLabels?,
        val layoutUnreadable: Boolean,
        val screenWidth: Float?,
        val screenDistance: Float?,
        val visitCount: Int,
        val selectedSeat: SelectedSeat? = null,
        /** The hall's visits, newest first. */
        val visits: List<HallVisitRow> = emptyList(),
    ) : HallDetailUiState
}

/**
 * Hall detail (spec §6.3): header, the seat map (neutral until M4 adds colours),
 * the "Edit layout" / "Draw layout" entry to the editor and the visit history.
 */
class HallDetailViewModel(hallId: Long, hallRepository: HallRepository) : ViewModel() {

    private val selectedSeatId = MutableStateFlow<SeatId?>(null)

    val uiState: StateFlow<HallDetailUiState> =
        combine(hallRepository.observeHallDetail(hallId), selectedSeatId) { detail, selected ->
            detail?.toUiState(selected) ?: HallDetailUiState.NotFound
        }
            .catch { emit(HallDetailUiState.Error(LOAD_FAILED)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HallDetailUiState.Loading)

    /** A tap on the map: selects the seat, or clears the selection when it was already selected. */
    fun onSeatClick(seatId: SeatId) {
        selectedSeatId.update { if (it == seatId) null else seatId }
    }

    /** Selects a seat chosen by row and number (accessibility picker). */
    fun selectSeat(seatId: SeatId) {
        selectedSeatId.value = seatId
    }

    fun clearSelection() {
        selectedSeatId.value = null
    }

    private fun HallDetail.toUiState(selected: SeatId?): HallDetailUiState.Loaded {
        val layout = hall.layout
        val labels = this.labels
        val selectedSeat = selected?.let { id ->
            val label = labels?.labelOf(id) ?: return@let null
            SelectedSeat(id, label, layout?.let { viewingAngle(it, hall.screenWidth, hall.screenDistance, id) })
        }
        return HallDetailUiState.Loaded(
            hallId = hall.id,
            hallName = hall.name,
            cinemaName = cinema.name,
            layout = layout,
            labels = labels,
            layoutUnreadable = hall.layoutUnreadable,
            screenWidth = hall.screenWidth,
            screenDistance = hall.screenDistance,
            visitCount = visits.size,
            selectedSeat = selectedSeat,
            visits = visits.map { visit ->
                HallVisitRow(
                    visitId = visit.id,
                    filmTitle = visit.filmTitle,
                    date = visit.date,
                    seatLabel = visit.seatId?.let { labels?.labelOf(it) },
                    format = visit.screeningFormat,
                    seatRating = visit.seatRating,
                )
            },
        )
    }

    companion object {
        const val LOAD_FAILED = "Couldn't load this hall."

        fun factory(hallId: Long, hallRepository: HallRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { HallDetailViewModel(hallId, hallRepository) }
        }
    }
}
