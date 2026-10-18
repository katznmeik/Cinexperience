package io.github.katznmeik.cinexperience.feature.visits.edit

import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.CinemaCategory
import io.github.katznmeik.cinexperience.core.model.CinemaRatings
import io.github.katznmeik.cinexperience.core.model.HallCategory
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.ShowingTime
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.TicketDraft
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * The visit form's in-progress state, kept in the ViewModel's `SavedStateHandle`
 * so that an edit survives process death, in particular while the camera app is
 * in front (spec §6.4 photo).
 *
 * @property ticket an imported ticket not saved yet (spec §6.10), [baselineTicket]
 *   the one the form was opened with; [importNotes] the import's notes. A draft
 *   saved before they existed has none.
 */
@Serializable
internal data class VisitDraft(
    val form: FormDraft,
    val baseline: FormDraft,
    val visitId: Long,
    val isEdit: Boolean,
    val sessionPhotos: List<String>,
    val pendingCapture: String?,
    val ticket: TicketDraftData? = null,
    val baselineTicket: TicketDraftData? = null,
    val importNotes: List<String> = emptyList(),
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** `null` if [text] is missing or can't be read. */
        fun decodeOrNull(text: String?): VisitDraft? = text?.let {
            try {
                JSON.decodeFromString(serializer(), it)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * [VisitForm] with plain values; ratings are 0 when not rated. [startTime] is
 * "HH:mm" or `null` (a draft saved before it existed has none).
 */
@Serializable
internal data class FormDraft(
    val hallId: Long?,
    val cinemaId: Long?,
    val date: String,
    val filmTitle: String,
    val seatId: Int?,
    val format: String?,
    val seatRating: Int,
    val hallRatings: List<Int>,
    val cinemaRatings: List<Int>,
    val note: String,
    val photoPath: String?,
    val startTime: String? = null,
) {
    fun toForm(): VisitForm = VisitForm(
        hallId = hallId,
        cinemaId = cinemaId,
        date = LocalDate.parse(date),
        filmTitle = filmTitle,
        seatId = seatId?.takeIf { it > 0 }?.let(::SeatId),
        format = format?.let { key -> ScreeningFormat.entries.firstOrNull { it.storageKey == key } },
        seatRating = Rating.ofOrNull(seatRating),
        hallRatings = HallCategory.entries.foldIndexed(HallRatings.NONE) { i, acc, c -> acc.withRating(c, Rating.ofOrNull(hallRatings.getOrNull(i))) },
        cinemaRatings = CinemaCategory.entries.foldIndexed(CinemaRatings.NONE) { i, acc, c ->
            acc.withRating(c, Rating.ofOrNull(cinemaRatings.getOrNull(i)))
        },
        note = note,
        photoPath = photoPath,
        startTime = startTime?.let { startTimeOrNull(it) },
    )

    companion object {
        fun of(form: VisitForm): FormDraft = FormDraft(
            hallId = form.hallId,
            cinemaId = form.cinemaId,
            date = form.date.toString(),
            filmTitle = form.filmTitle,
            seatId = form.seatId?.value,
            format = form.format?.storageKey,
            seatRating = form.seatRating?.value ?: 0,
            hallRatings = HallCategory.entries.map { form.hallRatings[it]?.value ?: 0 },
            cinemaRatings = CinemaCategory.entries.map { form.cinemaRatings[it]?.value ?: 0 },
            note = form.note,
            photoPath = form.photoPath,
            startTime = form.startTime?.toString(), // "HH:mm": a visit's start time is to the minute
        )

        /** [text] as a start time, `null` if it isn't a valid one (the draft is restored without it). */
        private fun startTimeOrNull(text: String): LocalTime? = try {
            LocalTime.parse(text).takeIf { ShowingTime.isWholeMinute(it) }
        } catch (e: DateTimeParseException) {
            null
        }
    }
}

/**
 * A [TicketDraft] with plain values: the booking number, the seats and the QR code
 * as [QrModules.encode] text. [toDraft] checks them again, like the route arguments.
 */
@Serializable
internal data class TicketDraftData(
    val bookingNumber: String,
    val seats: List<TicketSeatData>,
    val qrModules: String? = null,
) {
    /** `null` without a valid booking number; seats and a QR code that can't be read are left out. */
    fun toDraft(): TicketDraft? {
        val number = BookingNumber.ofOrNull(bookingNumber) ?: return null
        val ticketSeats = seats.mapNotNull { it.toSeatOrNull() }.distinct().take(Ticket.MAX_SEATS)
        return TicketDraft(number, ticketSeats, qrModules?.let(QrModules::decodeOrNull))
    }

    companion object {
        fun of(draft: TicketDraft): TicketDraftData = TicketDraftData(
            bookingNumber = draft.bookingNumber.value,
            seats = draft.seats.map { TicketSeatData(it.row, it.seat) },
            qrModules = draft.qrModules?.encode(),
        )
    }
}

/** A [TicketSeat] with plain values. */
@Serializable
internal data class TicketSeatData(val row: String?, val seat: String) {
    fun toSeatOrNull(): TicketSeat? = TicketSeat.ofOrNull(row, seat)
}
