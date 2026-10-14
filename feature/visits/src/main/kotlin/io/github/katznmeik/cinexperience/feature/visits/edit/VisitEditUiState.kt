package io.github.katznmeik.cinexperience.feature.visits.edit

import io.github.katznmeik.cinexperience.core.model.CinemaRatings
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.LayoutLabels
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.Visit
import java.time.LocalDate

/** A cinema the form's cinema picker offers, with its halls (natural name order). */
data class CinemaOption(val id: Long, val name: String, val halls: List<Hall>)

/**
 * What the user has entered. Text fields hold the raw input; the repository trims
 * them and stores blank texts as `null`.
 *
 * @property cinemaId the cinema chosen while no hall is chosen yet; `null` once a
 *   hall is chosen (the hall's cinema is the visit's cinema then).
 */
data class VisitForm(
    val hallId: Long? = null,
    val cinemaId: Long? = null,
    val date: LocalDate,
    val filmTitle: String = "",
    val seatId: SeatId? = null,
    val format: ScreeningFormat? = null,
    val seatRating: Rating? = null,
    val hallRatings: HallRatings = HallRatings.NONE,
    val cinemaRatings: CinemaRatings = CinemaRatings.NONE,
    val note: String = "",
    val photoPath: String? = null,
) {
    /** The form as it would be saved: compared to detect unsaved changes. */
    internal fun normalized(): VisitForm = copy(filmTitle = filmTitle.trim(), note = note.trim())

    internal fun toVisit(visitId: Long): Visit = Visit(
        id = visitId,
        hallId = requireNotNull(hallId) { "No hall chosen" },
        date = date,
        filmTitle = filmTitle,
        seatId = seatId,
        screeningFormat = format,
        hallRatings = hallRatings,
        seatRating = seatRating,
        cinemaRatings = cinemaRatings,
        note = note,
        photoPath = photoPath,
    )

    companion object {
        internal fun of(visit: Visit): VisitForm = VisitForm(
            hallId = visit.hallId,
            date = visit.date,
            filmTitle = visit.filmTitle.orEmpty(),
            seatId = visit.seatId,
            format = visit.screeningFormat,
            seatRating = visit.seatRating,
            hallRatings = visit.hallRatings,
            cinemaRatings = visit.cinemaRatings,
            note = visit.note.orEmpty(),
            photoPath = visit.photoPath,
        )
    }
}

enum class VisitEditStatus { Loading, Ready, NotFound, LoadFailed }

sealed interface VisitEditDialog {
    data object ConfirmDiscard : VisitEditDialog
    data object ConfirmDelete : VisitEditDialog
}

/**
 * The full-screen seat picker: [seatId] is the seat tapped so far (confirmed into
 * the form with "Use this seat").
 */
data class SeatPickerState(val seatId: SeatId?)

/**
 * The visit form (spec §6.4).
 *
 * @property seatLabel label of the form's seat in the hall's current layout;
 *   `null` without a seat, or when the seat is no longer in the layout
 *   ([seatMissing]), or the layout can't be read ([layoutUnreadable]).
 * @property seatPickerUnavailable why the seat can't be picked (no hall chosen,
 *   no layout); `null` when it can.
 * @property cameraRequest a capture URI the screen should hand to the camera app now.
 */
data class VisitEditUiState(
    val status: VisitEditStatus = VisitEditStatus.Loading,
    val isEdit: Boolean = false,
    val form: VisitForm,
    val cinemas: List<CinemaOption> = emptyList(),
    val cinemaName: String? = null,
    val hallName: String? = null,
    val halls: List<Hall> = emptyList(),
    val layout: SeatLayout? = null,
    val labels: LayoutLabels? = null,
    val screenWidth: Float? = null,
    val screenDistance: Float? = null,
    /** The screen's sideways shift in seat widths (spec §4.2), for the seat picker's map. */
    val screenOffset: Float = 0f,
    val seatLabel: SeatLabel? = null,
    val seatMissing: Boolean = false,
    val layoutUnreadable: Boolean = false,
    val seatPickerUnavailable: String? = null,
    val seatPicker: SeatPickerState? = null,
    val hasUnsavedChanges: Boolean = false,
    val isSaving: Boolean = false,
    val isPhotoBusy: Boolean = false,
    val cameraRequest: String? = null,
    val dialog: VisitEditDialog? = null,
    val message: String? = null,
    val isClosed: Boolean = false,
) {
    /** Save is always allowed once a hall is chosen (spec §6.4). */
    val canSave: Boolean get() = status == VisitEditStatus.Ready && form.hallId != null && !isSaving && !isPhotoBusy && !isClosed
}
