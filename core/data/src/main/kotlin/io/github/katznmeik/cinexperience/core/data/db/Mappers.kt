package io.github.katznmeik.cinexperience.core.data.db

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.CinemaRatings
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatOverride
import io.github.katznmeik.cinexperience.core.model.Visit

internal fun CinemaEntity.toModel() = Cinema(
    id = id,
    name = name,
    address = address,
    notes = notes,
    isPreloaded = isPreloaded,
    seedKey = seedKey,
)

internal fun HallEntity.toModel() = Hall(
    id = id,
    cinemaId = cinemaId,
    name = name,
    layout = layoutJson?.let(LayoutJson::decode),
    screenWidth = screenWidth,
    screenDistance = screenDistance,
    referenceImagePath = referenceImagePath,
    notes = notes,
    isPreloaded = isPreloaded,
    seedKey = seedKey,
)

internal fun VisitEntity.toModel() = Visit(
    id = id,
    hallId = hallId,
    date = date,
    filmTitle = filmTitle,
    seatId = seatId?.let(::SeatId),
    screeningFormat = screeningFormat?.let(ScreeningFormat::fromStorageKey),
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
)

internal fun SeatOverrideEntity.toModel() = SeatOverride(
    hallId = hallId,
    seatId = SeatId(seatId),
    colour = colour?.let(SeatColour::fromStorageKey),
    isFavourite = isFavourite,
)

internal fun SeatOverride.toEntity() = SeatOverrideEntity(
    hallId = hallId,
    seatId = seatId.value,
    colour = colour?.storageKey,
    isFavourite = isFavourite,
)
