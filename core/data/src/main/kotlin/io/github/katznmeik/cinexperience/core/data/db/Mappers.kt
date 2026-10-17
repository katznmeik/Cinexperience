package io.github.katznmeik.cinexperience.core.data.db

import android.util.Log
import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.data.json.TicketSeatsJson
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.CinemaRatings
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatOverride
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.Visit

internal fun CinemaEntity.toModel() = Cinema(
    id = id,
    name = name,
    address = address,
    notes = notes,
    isPreloaded = isPreloaded,
    seedKey = seedKey,
    isFavourite = isFavourite,
)

/**
 * Never throws for a bad layout: an unreadable `layout_json` (corrupt, or written
 * by a newer app) gives `layout = null` and `layoutUnreadable = true`, so one bad
 * row cannot break a whole Flow. The stored JSON stays untouched.
 *
 * @param maxDataSeatId highest seat id referenced by the hall's visits and
 *   overrides, so [Hall.nextSeatId] is the full effective mark (see seatIdMark).
 */
internal fun HallEntity.toModel(maxDataSeatId: Int = 0): Hall {
    val layout = layoutJson?.let(LayoutJson::decodeOrNull)
    val unreadable = layoutJson != null && layout == null
    if (unreadable) Log.w(TAG, "Unreadable layout of hall $id; showing it without layout")
    return Hall(
        id = id,
        cinemaId = cinemaId,
        name = name,
        layout = layout,
        screenWidth = screenWidth,
        screenDistance = screenDistance,
        screenOffset = screenOffset,
        referenceImagePath = referenceImagePath,
        notes = notes,
        isPreloaded = isPreloaded,
        seedKey = seedKey,
        nextSeatId = maxOf(nextSeatId, layout?.nextSeatId ?: SeatLayout.FIRST_SEAT_ID, maxDataSeatId + 1),
        layoutUnreadable = unreadable,
    )
}

private const val TAG = "CinexData"

internal fun maxSeatId(visits: List<VisitEntity>, overrides: List<SeatOverrideEntity>): Int =
    maxOf(visits.maxOfOrNull { it.seatId ?: 0 } ?: 0, overrides.maxOfOrNull { it.seatId } ?: 0)

internal fun VisitEntity.toModel() = Visit(
    id = id,
    hallId = hallId,
    date = date,
    filmTitle = filmTitle,
    seatId = seatId?.let(::SeatId),
    // Unknown keys (e.g. from a newer app version) read as "not set" instead of failing the Flow.
    screeningFormat = screeningFormat?.let { key -> ScreeningFormat.entries.firstOrNull { it.storageKey == key } },
    hallRatings = HallRatings(
        sound = Rating.ofOrNull(ratingSound),
        picture = Rating.ofOrNull(ratingPicture),
        screenSize = Rating.ofOrNull(ratingScreenSize),
        seatComfort = Rating.ofOrNull(ratingSeatComfort),
        legroom = Rating.ofOrNull(ratingLegroom),
        steepness = Rating.ofOrNull(ratingSteepness),
    ),
    seatRating = Rating.ofOrNull(seatRating),
    cinemaRatings = CinemaRatings(
        atmosphere = Rating.ofOrNull(cinemaRatingAtmosphere),
        barSnacks = Rating.ofOrNull(cinemaRatingBarSnacks),
        popcorn = Rating.ofOrNull(cinemaRatingPopcorn),
        seatComfort = Rating.ofOrNull(cinemaRatingSeatComfort),
    ),
    note = note,
    photoPath = photoPath,
    startTime = startTime,
)

internal fun Visit.toEntity() = VisitEntity(
    id = id,
    hallId = hallId,
    date = date,
    filmTitle = filmTitle,
    seatId = seatId?.value,
    screeningFormat = screeningFormat?.storageKey,
    ratingSound = hallRatings.sound?.value,
    ratingPicture = hallRatings.picture?.value,
    ratingScreenSize = hallRatings.screenSize?.value,
    ratingSeatComfort = hallRatings.seatComfort?.value,
    ratingLegroom = hallRatings.legroom?.value,
    ratingSteepness = hallRatings.steepness?.value,
    seatRating = seatRating?.value,
    cinemaRatingAtmosphere = cinemaRatings.atmosphere?.value,
    cinemaRatingBarSnacks = cinemaRatings.barSnacks?.value,
    cinemaRatingPopcorn = cinemaRatings.popcorn?.value,
    cinemaRatingSeatComfort = cinemaRatings.seatComfort?.value,
    note = note,
    photoPath = photoPath,
    startTime = startTime,
)

/**
 * Never throws: a row this app can't read (only damaged data; the repository
 * writes nothing else) gives `null`, so it can't break a Flow, and QR modules it
 * can't read give a ticket without them. The stored row stays untouched.
 */
internal fun TicketEntity.toModel(): Ticket? {
    val number = BookingNumber.ofOrNull(bookingNumber)
    val seatList = TicketSeatsJson.decodeOrNull(seats)
    if (number == null || seatList == null) {
        Log.w(TAG, "Unreadable ticket of visit $visitId")
        return null
    }
    val modules = qrModules?.let(QrModules::decodeOrNull)
    if (qrModules != null && modules == null) Log.w(TAG, "Unreadable QR code of the ticket of visit $visitId")
    return Ticket(visitId, number, seatList, modules)
}

internal fun Ticket.toEntity() = TicketEntity(
    visitId = visitId,
    bookingNumber = bookingNumber.value,
    seats = TicketSeatsJson.encode(seats),
    qrModules = qrModules?.encode(),
)

internal fun SeatOverrideEntity.toModel() = SeatOverride(
    hallId = hallId,
    seatId = SeatId(seatId),
    colour = colour?.let { key -> SeatColour.entries.firstOrNull { it.storageKey == key } },
    isFavourite = isFavourite,
)

internal fun SeatOverride.toEntity() = SeatOverrideEntity(
    hallId = hallId,
    seatId = seatId.value,
    colour = colour?.storageKey,
    isFavourite = isFavourite,
)
