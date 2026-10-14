package io.github.katznmeik.cinexperience.feature.seatmap

import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatOverride
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.SampleData
import io.github.katznmeik.cinexperience.core.testing.SampleLayouts
import java.time.LocalDate

/**
 * Delphi LUX Saal 1 drawn as [SampleLayouts.splitHall] with INVENTED visits, seat
 * ratings and manual colours: a realistic mix of great, OK, avoid and no-data
 * seats and a favourite (row 7, seat 10).
 */
internal object RatedHallFixture {
    const val HALL = SampleData.DELPHI_LUX_SAAL_1

    /** The seat at [index] among the seats of grid row [row] (left to right, all blocks). */
    fun seat(row: Int, index: Int): SeatId = SampleLayouts.splitHall.rows[row].seatIds[index]

    /** Row 7, Seat 10 (centre block): three visits, rated 5, 5 and 4; the favourite. */
    val favourite: SeatId = seat(6, 13)

    /** Row 8, Seat 9: one visit rated 4 (great by ratings, also set great manually). */
    val rowEightSeatNine: SeatId = seat(7, 12)

    /** Row 2, left block: one visit rated 2, so "avoid" from its rating. */
    val ratedAvoid: SeatId = seat(1, 2)

    /** Row 5, centre block: one visit rated 3, so "OK" from its rating. */
    val ratedOk: SeatId = seat(4, 10)

    /** Row 6, centre block: set to "avoid" manually amid great seats. */
    val manualAvoid: SeatId = seat(5, 9)

    /** Row 9, right block: never visited, no colour. */
    val noData: SeatId = seat(8, 15)

    private fun ratings(vararg values: Int?): HallRatings {
        val r = values.map { it?.let(::Rating) }
        return HallRatings(r[0], r[1], r[2], r[3], r[4], r[5])
    }

    /**
     * Six visits. Hall scores: sound 4.75 (×4), picture 4.25 (×4), screen size
     * 4.75 (×4), seat comfort 3.75 (×4), legroom 3.25 (×4), steepness 3.67 (×3);
     * overall ≈ 4.07.
     */
    val visits: List<Visit> = listOf(
        Visit(
            id = 9_101, hallId = HALL, date = LocalDate.of(2026, 10, 3), filmTitle = "Perfect Days",
            seatId = favourite, screeningFormat = ScreeningFormat.OMU, seatRating = Rating(5),
            hallRatings = ratings(5, 4, 5, 4, 3, 4),
        ),
        Visit(
            id = 9_102, hallId = HALL, date = LocalDate.of(2026, 9, 19), filmTitle = "The Brutalist",
            seatId = favourite, screeningFormat = ScreeningFormat.SEVENTY_MM, seatRating = Rating(5),
            hallRatings = ratings(5, 5, 5, 4, 4, null),
        ),
        Visit(
            id = 9_103, hallId = HALL, date = LocalDate.of(2026, 9, 6), filmTitle = "Aftersun",
            seatId = favourite, screeningFormat = ScreeningFormat.OV, seatRating = Rating(4),
        ),
        Visit(
            id = 9_104, hallId = HALL, date = LocalDate.of(2026, 8, 30), filmTitle = "Anora",
            seatId = rowEightSeatNine, screeningFormat = ScreeningFormat.OMU, seatRating = Rating(4),
            hallRatings = ratings(4, 4, 5, 3, 3, 4),
        ),
        Visit(
            id = 9_105, hallId = HALL, date = LocalDate.of(2026, 8, 12),
            seatId = ratedAvoid, seatRating = Rating(2),
            hallRatings = ratings(5, 4, 4, 4, 3, 3),
        ),
        Visit(
            id = 9_106, hallId = HALL, date = LocalDate.of(2026, 7, 25), filmTitle = "Past Lives",
            seatId = ratedOk, screeningFormat = ScreeningFormat.OV, seatRating = Rating(3),
        ),
    )

    /** Manual colours: front row avoid, the middle of rows 6–8 great, rows 3–4 OK, one broken seat. */
    val overrides: List<SeatOverride> = buildList {
        fun colour(row: Int, indices: IntRange, colour: SeatColour) =
            indices.forEach { add(SeatOverride(HALL, seat(row, it), colour)) }
        colour(0, 2..13, SeatColour.RED)
        colour(2, 6..11, SeatColour.YELLOW)
        colour(3, 6..10, SeatColour.YELLOW)
        colour(2, 0..1, SeatColour.YELLOW)
        colour(5, 6..8, SeatColour.GREEN)
        colour(5, 10..12, SeatColour.GREEN)
        colour(6, 7..12, SeatColour.GREEN)
        colour(7, 7..12, SeatColour.GREEN)
        add(SeatOverride(HALL, manualAvoid, SeatColour.RED))
        add(SeatOverride(HALL, favourite, isFavourite = true))
    }

    /** [SampleData.storeWithSplitHall] with Saal 1's visits and overrides replaced by this fixture. */
    fun store(): FakeStore = SampleData.storeWithSplitHall().let { s ->
        s.copy(visits = s.visits.filter { it.hallId != HALL } + visits, overrides = overrides)
    }
}
