package io.github.katznmeik.cinexperience.feature.visits.edit

import io.github.katznmeik.cinexperience.core.model.CinemaCategory
import io.github.katznmeik.cinexperience.core.model.CinemaRatings
import io.github.katznmeik.cinexperience.core.model.HallCategory
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * The visit form's in-progress state, kept in the ViewModel's `SavedStateHandle`
 * so that an edit survives process death, in particular while the camera app is
 * in front (spec §6.4 photo).
 */
@Serializable
internal data class VisitDraft(
    val form: FormDraft,
    val baseline: FormDraft,
    val visitId: Long,
    val isEdit: Boolean,
    val sessionPhotos: List<String>,
    val pendingCapture: String?,
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

/** [VisitForm] with plain values; ratings are 0 when not rated. */
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
        )
    }
}
