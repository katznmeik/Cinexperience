package io.github.katznmeik.cinexperience.core.model

import java.time.LocalDate

/**
 * A cinema (spec §4.1).
 *
 * @property id database id; `0` for a cinema that has not been saved yet.
 * @property isPreloaded shipped with the app's seed data; preloaded cinemas can be
 *   renamed and edited but not deleted.
 * @property seedKey stable key of a preloaded cinema in the seed asset (§9), `null`
 *   for user-added cinemas. Never shown to the user.
 * @property isFavourite marked as a favourite cinema by the user (§6.1, §6.2);
 *   changed only through `CinemaRepository.setFavourite`.
 */
data class Cinema(
    val id: Long = 0,
    val name: String,
    val address: String? = null,
    val notes: String? = null,
    val isPreloaded: Boolean = false,
    val seedKey: String? = null,
    val isFavourite: Boolean = false,
)

/**
 * A hall (spec §4.2).
 *
 * @property layout `null` until the user draws it (or the seed supplies one).
 * @property screenWidth screen width in seat widths (grid columns).
 * @property screenDistance gap between the screen and row 0, in row depths.
 * @property screenOffset horizontal shift of the screen's centre from the grid's
 *   centre, in seat widths (0.5 steps; negative = to the left as seen from the
 *   seats facing the screen); 0 = centred. Must be [ScreenOffsetRules.isValid]:
 *   the repositories reject other values.
 * @property referenceImagePath app-private relative path of the Yorck-app
 *   screenshot used in the layout editor (§6.5), relative to the app's files dir.
 * @property isPreloaded see [Cinema.isPreloaded]; preloaded halls cannot be deleted.
 * @property seedKey see [Cinema.seedKey], e.g. `"delphi-lux/saal-3"`.
 * @property nextSeatId the hall's seat id high-water mark: every id below it may
 *   have been used by an earlier layout of this hall, so new seats must get ids at
 *   or above it (see [SeatId], [LayoutEditSession]). Maintained by the repository;
 *   not editable.
 * @property layoutUnreadable the stored layout could not be read (e.g. written by
 *   a newer app version). [layout] is then `null`, but the stored layout is kept
 *   until a new layout is saved.
 */
data class Hall(
    val id: Long = 0,
    val cinemaId: Long,
    val name: String,
    val layout: SeatLayout? = null,
    val screenWidth: Float? = null,
    val screenDistance: Float? = null,
    val screenOffset: Float = ScreenOffsetRules.CENTRED,
    val referenceImagePath: String? = null,
    val notes: String? = null,
    val isPreloaded: Boolean = false,
    val seedKey: String? = null,
    val nextSeatId: Int = SeatLayout.FIRST_SEAT_ID,
    val layoutUnreadable: Boolean = false,
) {
    /** The hall has a stored seat layout (even if this app version can't read it). */
    val isLayoutDrawn: Boolean get() = layout != null || layoutUnreadable

    /** Starts editing this hall's layout (spec §6.5) with correct seat id handling. */
    fun startLayoutEdit(): LayoutEditSession = LayoutEditSession.start(layout, nextSeatId)
}

/**
 * Screening format of a visit (spec §4.4). [storageKey] is what the database and
 * backups store; it must never change for an existing entry.
 */
enum class ScreeningFormat(val storageKey: String, val displayName: String) {
    OV("OV", "OV"),
    OMU("OMU", "OmU"),
    GERMAN_DUB("GERMAN_DUB", "German dub"),
    THREE_D("3D", "3D"),
    SEVENTY_MM("70MM", "70mm"),
    OTHER("OTHER", "Other"),
    ;

    companion object {
        fun fromStorageKey(key: String): ScreeningFormat =
            entries.firstOrNull { it.storageKey == key }
                ?: throw IllegalArgumentException("Unknown screening format '$key'")
    }
}

/**
 * One cinema visit (spec §4.4, §4.5). Everything except [hallId] and [date] is optional.
 *
 * @property seatId the seat sat in, see [SeatId]; may point to a seat that was
 *   later removed from the layout (then it has no label any more).
 * @property cinemaRatings the cinema-level ratings (§4.5), stored per visit.
 * @property photoPath app-private relative path of the photo (see VisitPhotoStore).
 */
data class Visit(
    val id: Long = 0,
    val hallId: Long,
    val date: LocalDate,
    val filmTitle: String? = null,
    val seatId: SeatId? = null,
    val screeningFormat: ScreeningFormat? = null,
    val hallRatings: HallRatings = HallRatings.NONE,
    val seatRating: Rating? = null,
    val cinemaRatings: CinemaRatings = CinemaRatings.NONE,
    val note: String? = null,
    val photoPath: String? = null,
)

/** Manual seat colour (spec §4.6) and the automatic seat colours (spec §5). */
enum class SeatColour(val storageKey: String) {
    GREEN("GREEN"),
    YELLOW("YELLOW"),
    RED("RED"),
    ;

    companion object {
        fun fromStorageKey(key: String): SeatColour =
            entries.firstOrNull { it.storageKey == key }
                ?: throw IllegalArgumentException("Unknown seat colour '$key'")
    }
}

/**
 * Manual per-seat settings (spec §4.6), keyed by `(hallId, seatId)`.
 *
 * @property colour manual colour; `null` = automatic.
 * @property isFavourite at most one favourite per hall, see [SeatOverrides.withFavourite].
 */
data class SeatOverride(
    val hallId: Long,
    val seatId: SeatId,
    val colour: SeatColour? = null,
    val isFavourite: Boolean = false,
) {
    /** An override without colour and favourite carries no information and is not stored. */
    val isEmpty: Boolean get() = colour == null && !isFavourite
}
