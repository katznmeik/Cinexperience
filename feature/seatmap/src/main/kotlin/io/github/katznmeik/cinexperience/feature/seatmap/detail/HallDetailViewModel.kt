package io.github.katznmeik.cinexperience.feature.seatmap.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.data.repository.SeatOverrideRepository
import io.github.katznmeik.cinexperience.core.model.HallCategory
import io.github.katznmeik.cinexperience.core.model.HallDetail
import io.github.katznmeik.cinexperience.core.model.HallScores
import io.github.katznmeik.cinexperience.core.model.LayoutLabels
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.ViewingAngle
import io.github.katznmeik.cinexperience.core.model.viewingAngle
import io.github.katznmeik.cinexperience.core.seatmapui.SeatVisual
import io.github.katznmeik.cinexperience.core.seatmapui.toSeatVisual
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

/**
 * The seat tapped on the hall's map, with what the selected-seat card shows (spec §6.3).
 *
 * @property visual how the seat is drawn (colour and favourite).
 * @property overrideColour the manual colour; `null` = automatic ("Auto").
 * @property averageRating mean seat rating of the visits in this seat, `null` without ratings.
 * @property visitCount visits in this seat, rated or not.
 */
data class SelectedSeat(
    val seatId: SeatId,
    val label: SeatLabel,
    val viewingAngle: ViewingAngle?,
    val visual: SeatVisual = SeatVisual.Neutral,
    val overrideColour: SeatColour? = null,
    val averageRating: Double? = null,
    val visitCount: Int = 0,
) {
    val isFavourite: Boolean get() = visual.isFavourite
}

/**
 * One category of the hall's score breakdown (spec §5, §6.3).
 *
 * @property score mean of the visits' ratings, `null` when unrated (shown as "–").
 * @property count number of ratings.
 */
data class CategoryScoreRow(val category: HallCategory, val score: Double?, val count: Int) {
    /** Short label of the breakdown ("Steepness" instead of "Seating steepness"). */
    val label: String get() = if (category == HallCategory.STEEPNESS) "Steepness" else category.displayName
}

/** The six category rows of [scores], in the spec's order, unrated ones included. */
internal fun categoryRows(scores: HallScores): List<CategoryScoreRow> = HallCategory.entries.map { category ->
    val score = scores[category]
    CategoryScoreRow(category, score?.average, score?.count ?: 0)
}

sealed interface HallDetailUiState {
    data object Loading : HallDetailUiState
    data object NotFound : HallDetailUiState
    data class Error(val message: String) : HallDetailUiState

    /**
     * @property layout `null` when the hall has no layout yet or it can't be read
     *   ([layoutUnreadable]).
     * @property overallScore hall overall score (spec §5), `null` when unrated.
     * @property scores the six categories, always all six.
     * @property seatVisuals colour and favourite of every seat of [layout].
     * @property message one-off message (e.g. a failed save) for a snackbar.
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
        val overallScore: Double? = null,
        val scores: List<CategoryScoreRow> = categoryRows(HallScores.EMPTY),
        val seatVisuals: Map<SeatId, SeatVisual> = emptyMap(),
        val message: String? = null,
    ) : HallDetailUiState
}

/**
 * Hall detail (spec §6.3): header with the overall score, the coloured seat map,
 * the selected seat with its colour override and favourite, the score breakdown,
 * the "Edit layout" / "Draw layout" entry to the editor and the visit history.
 * Colours and scores come from the repository's [HallDetail] (core:model functions).
 */
class HallDetailViewModel(
    private val hallId: Long,
    hallRepository: HallRepository,
    private val seatOverrideRepository: SeatOverrideRepository,
) : ViewModel() {

    private val selectedSeatId = MutableStateFlow<SeatId?>(null)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HallDetailUiState> =
        combine(hallRepository.observeHallDetail(hallId), selectedSeatId, message) { detail, selected, message ->
            detail?.toUiState(selected, message) ?: HallDetailUiState.NotFound
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

    /** Sets the manual colour of [seatId]; `null` = "Auto" (back to the colour from its ratings). */
    fun setSeatColour(seatId: SeatId, colour: SeatColour?) {
        write(COLOUR_FAILED) { seatOverrideRepository.setSeatColour(hallId, seatId, colour) }
    }

    /**
     * Makes [seatId] the hall's favourite, or removes the favourite when it already
     * is. A favourite on another seat moves here (one per hall, spec §4.6).
     */
    fun toggleFavourite(seatId: SeatId) {
        val isFavourite = (uiState.value as? HallDetailUiState.Loaded)?.seatVisuals?.get(seatId)?.isFavourite == true
        write(FAVOURITE_FAILED) { seatOverrideRepository.setFavouriteSeat(hallId, if (isFavourite) null else seatId) }
    }

    /** The snackbar showed [HallDetailUiState.Loaded.message]. */
    fun messageShown() {
        message.value = null
    }

    private fun write(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                // The seat (or hall) was removed in the meantime, e.g. by a layout edit.
                message.value = SEAT_GONE
            } catch (e: Exception) {
                message.value = failure
            }
        }
    }

    private fun HallDetail.toUiState(selected: SeatId?, message: String?): HallDetailUiState.Loaded {
        val layout = hall.layout
        val labels = this.labels
        val visuals = seats.mapValues { (_, state) -> state.toSeatVisual() }
        val selectedSeat = selected?.let { id ->
            val label = labels?.labelOf(id) ?: return@let null
            val state = seats[id]
            SelectedSeat(
                seatId = id,
                label = label,
                viewingAngle = layout?.let { viewingAngle(it, hall.screenWidth, hall.screenDistance, id) },
                visual = visuals[id] ?: SeatVisual.Neutral,
                overrideColour = state?.overrideColour,
                averageRating = state?.averageRating,
                visitCount = state?.visitCount ?: 0,
            )
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
            overallScore = scores.overall,
            scores = categoryRows(scores),
            seatVisuals = visuals,
            message = message,
        )
    }

    companion object {
        const val LOAD_FAILED = "Couldn't load this hall."
        const val COLOUR_FAILED = "Couldn't save the seat colour."
        const val FAVOURITE_FAILED = "Couldn't save the favourite seat."
        const val SEAT_GONE = "This seat is no longer in the hall's layout."

        fun factory(
            hallId: Long,
            hallRepository: HallRepository,
            seatOverrideRepository: SeatOverrideRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { HallDetailViewModel(hallId, hallRepository, seatOverrideRepository) }
        }
    }
}
