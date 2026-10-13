package io.github.katznmeik.cinexperience.core.model

/** Where a seat's displayed colour comes from (spec §5). */
enum class SeatColourSource {
    /** The user set the colour manually. */
    OVERRIDE,

    /** Average of the visit seat ratings. */
    RATINGS,

    /** No override and no seat ratings: neutral / grey. */
    NONE,
}

/**
 * Automatic colour from seat ratings (spec §5): average ≥ 4.0 → green,
 * ≥ 2.5 → yellow, below → red; `null` when there are no ratings. Compared with
 * integer arithmetic, so the thresholds are exact.
 */
fun seatColourForRatings(ratings: Iterable<Rating>): SeatColour? {
    var sum = 0
    var count = 0
    ratings.forEach {
        sum += it.value
        count++
    }
    return when {
        count == 0 -> null
        sum >= 4 * count -> SeatColour.GREEN // average ≥ 4.0
        2 * sum >= 5 * count -> SeatColour.YELLOW // average ≥ 2.5
        else -> SeatColour.RED
    }
}

/** Colour resolution (spec §5): override → ratings → none. `null` colour means neutral. */
fun resolveSeatColour(override: SeatColour?, ratings: Iterable<Rating>): Pair<SeatColour?, SeatColourSource> {
    if (override != null) return override to SeatColourSource.OVERRIDE
    val automatic = seatColourForRatings(ratings)
    return automatic to if (automatic != null) SeatColourSource.RATINGS else SeatColourSource.NONE
}

/**
 * Everything the seat map and seat sheet (spec §6.3) need about one seat.
 *
 * @property colour displayed colour; `null` = neutral grey (no data).
 * @property overrideColour the manual colour, if any (also when it equals the automatic one).
 * @property automaticColour the colour the ratings alone would give.
 * @property averageRating mean seat rating, `null` without ratings.
 * @property ratingCount number of visits in this seat with a seat rating.
 * @property visitCount number of visits in this seat, rated or not.
 */
data class SeatState(
    val seatId: SeatId,
    val label: SeatLabel,
    val colour: SeatColour?,
    val colourSource: SeatColourSource,
    val overrideColour: SeatColour?,
    val automaticColour: SeatColour?,
    val averageRating: Double?,
    val ratingCount: Int,
    val visitCount: Int,
    val isFavourite: Boolean,
)

/**
 * Seat states for every seat of [layout] from the hall's [visits] and [overrides].
 * Visits and overrides pointing at seats that are not in the layout are ignored.
 */
fun seatStates(layout: SeatLayout, visits: List<Visit>, overrides: List<SeatOverride>): Map<SeatId, SeatState> {
    val labels = layout.labels()
    val visitsBySeat = visits.filter { it.seatId != null }.groupBy { it.seatId!! }
    val overrideBySeat = overrides.associateBy { it.seatId }
    val favourite = SeatOverrides.favourite(overrides)
    return labels.seats.mapValues { (seatId, info) ->
        val seatVisits = visitsBySeat[seatId].orEmpty()
        val ratings = seatVisits.mapNotNull { it.seatRating }
        val overrideColour = overrideBySeat[seatId]?.colour
        val (colour, source) = resolveSeatColour(overrideColour, ratings)
        SeatState(
            seatId = seatId,
            label = info.label,
            colour = colour,
            colourSource = source,
            overrideColour = overrideColour,
            automaticColour = seatColourForRatings(ratings),
            averageRating = categoryScore(ratings)?.average,
            ratingCount = ratings.size,
            visitCount = seatVisits.size,
            isFavourite = seatId == favourite,
        )
    }
}

/** Pure rules for changing a hall's [SeatOverride]s (spec §4.6). */
object SeatOverrides {

    /**
     * The hall's favourite seat. Normally at most one override is a favourite; if
     * stored data ever holds several, the lowest seat id wins so the result is stable.
     */
    fun favourite(overrides: List<SeatOverride>): SeatId? =
        overrides.filter { it.isFavourite }.minByOrNull { it.seatId.value }?.seatId

    /**
     * Sets or clears ([colour] = `null`) the manual colour of one seat. Returns the
     * complete new override list of the hall; overrides that become empty are dropped.
     */
    fun withColour(overrides: List<SeatOverride>, hallId: Long, seatId: SeatId, colour: SeatColour?): List<SeatOverride> =
        replace(overrides, hallId, seatId) { it.copy(colour = colour) }

    /**
     * Makes [seatId] the hall's only favourite, or clears the favourite when
     * [seatId] is `null`. Every other override loses its favourite flag, so the
     * result never has more than one favourite.
     */
    fun withFavourite(overrides: List<SeatOverride>, hallId: Long, seatId: SeatId?): List<SeatOverride> {
        val cleared = overrides
            .filter { it.hallId == hallId }
            .map { if (it.isFavourite) it.copy(isFavourite = false) else it }
        val result = if (seatId == null) cleared else replace(cleared, hallId, seatId) { it.copy(isFavourite = true) }
        return result.filterNot { it.isEmpty }
    }

    /** Removes the colour and the favourite flag of one seat. */
    fun cleared(overrides: List<SeatOverride>, hallId: Long, seatId: SeatId): List<SeatOverride> =
        overrides.filter { it.hallId == hallId && it.seatId != seatId && !it.isEmpty }

    private fun replace(
        overrides: List<SeatOverride>,
        hallId: Long,
        seatId: SeatId,
        change: (SeatOverride) -> SeatOverride,
    ): List<SeatOverride> {
        val forHall = overrides.filter { it.hallId == hallId }
        val existing = forHall.firstOrNull { it.seatId == seatId } ?: SeatOverride(hallId, seatId)
        val updated = change(existing)
        return (forHall.filter { it.seatId != seatId } + updated).filterNot { it.isEmpty }
    }
}
