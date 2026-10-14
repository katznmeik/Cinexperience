package io.github.katznmeik.cinexperience.core.testing

import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.CinemaRatings
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.NumberingDirection
import io.github.katznmeik.cinexperience.core.model.Rating
import io.github.katznmeik.cinexperience.core.model.SeatColour
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.SeatLayout
import io.github.katznmeik.cinexperience.core.model.SeatNumbering
import io.github.katznmeik.cinexperience.core.model.SeatOverride
import io.github.katznmeik.cinexperience.core.model.Visit
import java.time.LocalDate

/** Robolectric qualifiers for phone-sized screenshots in the dark theme. */
const val PHONE_QUALIFIERS = "w412dp-h915dp-port-night-xxhdpi"

/**
 * Illustrative test data for screenshots and ViewModel tests: the real preloaded
 * Yorck cinemas and halls (names and addresses from the seed asset), with
 * INVENTED visits, ratings, layouts and favourite seats. Never used by the app.
 */
object SampleData {
    const val DELPHI_LUX = 5L
    const val BABYLON = 1L
    const val PASSAGE = 11L

    /** Delphi LUX, Saal 1: layout drawn, favourite seat, several visits. */
    const val DELPHI_LUX_SAAL_1 = 501L

    /** Delphi LUX, Saal 6: no layout, no visits. */
    const val DELPHI_LUX_SAAL_6 = 506L

    private val seed: List<Triple<String, String, List<String>>> = listOf(
        Triple("Babylon Kreuzberg", "Dresdener Straße 126, 10999 Berlin", saale(2)),
        Triple("Capitol Dahlem", "Thielallee 36, 14195 Berlin", saale(1)),
        Triple("Cinema Paris", "Kurfürstendamm 211, 10719 Berlin", saale(1)),
        Triple("Delphi Filmpalast", "Kantstraße 12a, 10623 Berlin", saale(1)),
        Triple("delphi LUX", "Kantstraße 10, 10623 Berlin", saale(7)),
        Triple("Filmtheater am Friedrichshain", "Bötzowstraße 1–5, 10407 Berlin", saale(5)),
        Triple("International", "Karl-Marx-Allee 33, 10178 Berlin", saale(1)),
        Triple("Kant Kino", "Kantstraße 54, 10627 Berlin", saale(5)),
        Triple("Neues Off", "Hermannstraße 20, 12049 Berlin", saale(1)),
        Triple("Odeon", "Hauptstraße 116, 10827 Berlin", saale(1)),
        Triple("Passage", "Karl-Marx-Straße 131, 12043 Berlin", saale(4)),
        Triple("Rollberg", "Rollbergstraße 70, 12053 Berlin", saale(5)),
        Triple("Yorck", "Yorckstraße 86, 10965 Berlin", listOf("Yorck", "New Yorck")),
    )

    private fun saale(count: Int) = (1..count).map { "Saal $it" }

    /** Cinema ids 1…13 in seed order; hall ids are cinemaId * 100 + hall number. */
    val cinemas: List<Cinema> = seed.mapIndexed { index, (name, address, _) ->
        Cinema(
            id = index + 1L,
            name = name,
            address = address,
            isPreloaded = true,
            seedKey = name.lowercase().replace(' ', '-'),
        )
    }

    private val layouts: Map<Long, SeatLayout> = mapOf(
        DELPHI_LUX_SAAL_1 to SeatLayout.fromPatterns(List(12) { "S".repeat(15) }, firstSeatId = 1),
        502L to SeatLayout.fromPatterns(List(11) { "S".repeat(14) }, firstSeatId = 1),
        503L to SeatLayout.fromPatterns(List(7) { "S".repeat(14) }, firstSeatId = 1),
        504L to SeatLayout.fromPatterns(List(6) { "S".repeat(13) }, firstSeatId = 1),
    )

    val halls: List<Hall> = seed.flatMapIndexed { index, (_, _, hallNames) ->
        val cinemaId = index + 1L
        hallNames.mapIndexed { hallIndex, name ->
            val id = cinemaId * 100 + hallIndex + 1
            Hall(
                id = id,
                cinemaId = cinemaId,
                name = name,
                layout = layouts[id],
                isPreloaded = true,
                seedKey = "${cinemas[index].seedKey}/saal-${hallIndex + 1}",
            )
        }
    }

    private var visitId = 1L

    /** [count] visits to [hallId] whose six hall ratings are [hall] and cinema ratings [cinema]. */
    private fun visits(hallId: Long, count: Int, hall: Int?, cinema: List<Int?> = listOf(null, null, null, null)) =
        List(count) { n ->
            val h = hall?.let(::Rating)
            Visit(
                id = visitId++,
                hallId = hallId,
                date = LocalDate.of(2026, 9, 1).minusDays((hallId + n * 7) % 60),
                hallRatings = HallRatings(h, h, h, h, h, h),
                cinemaRatings = CinemaRatings(
                    atmosphere = cinema[0]?.let(::Rating),
                    barSnacks = cinema[1]?.let(::Rating),
                    popcorn = cinema[2]?.let(::Rating),
                    seatComfort = cinema[3]?.let(::Rating),
                ),
            )
        }

    val visits: List<Visit> =
        visits(DELPHI_LUX_SAAL_1, 2, hall = 5, cinema = listOf(5, 4, 4, 5)) +
            visits(DELPHI_LUX_SAAL_1, 2, hall = 4, cinema = listOf(5, 5, 4, 5)) +
            visits(502, 2, hall = 4, cinema = listOf(5, 4, 3, 4)) +
            visits(503, 1, hall = 4) +
            visits(504, 1, hall = 3) +
            visits(BABYLON * 100 + 1, 3, hall = 4, cinema = listOf(5, 4, null, 4)) +
            visits(BABYLON * 100 + 2, 2, hall = 4) +
            visits(701, 3, hall = 5, cinema = listOf(5, 4, 4, 4)) +
            visits(801, 2, hall = 4, cinema = listOf(4, 3, 3, 4)) +
            visits(802, 2, hall = 3) +
            visits(901, 1, hall = 3, cinema = listOf(4, 3, null, 3)) +
            visits(401, 2, hall = 4, cinema = listOf(5, 4, 4, 4)) +
            visits(1301, 1, hall = null) // a visit without ratings

    val favourites: Map<Long, SeatLabel> = mapOf(
        DELPHI_LUX_SAAL_1 to SeatLabel("7", 8),
        502L to SeatLabel("5", 7),
    )

    fun store(): FakeStore = FakeStore(cinemas, halls, visits, favourites)

    /** [store] with Delphi LUX Saal 1 drawn as the realistic [SampleLayouts.splitHall]. */
    fun storeWithSplitHall(): FakeStore = store().let { s ->
        s.copy(
            halls = s.halls.map {
                if (it.id == DELPHI_LUX_SAAL_1) {
                    it.copy(layout = SampleLayouts.splitHall, screenWidth = 16f, screenDistance = 2.5f)
                } else {
                    it
                }
            },
            favourites = s.favourites - DELPHI_LUX_SAAL_1,
        )
    }

    /**
     * [store] with Delphi LUX Saal 1 drawn as [SampleLayouts.yorckNumbering], without
     * visits, and with INVENTED colours on its wheelchair spaces: row 1 great, OK,
     * avoid, no data; row 6's "R" great and the favourite.
     */
    fun storeWithYorckNumbering(): FakeStore = store().let { s ->
        val hall = DELPHI_LUX_SAAL_1
        s.copy(
            halls = s.halls.map {
                if (it.id == hall) it.copy(layout = SampleLayouts.yorckNumbering, screenWidth = 13f, screenDistance = 2f) else it
            },
            visits = s.visits.filter { it.hallId != hall },
            favourites = s.favourites - hall,
            overrides = listOf(
                SeatOverride(hall, SeatId(1), SeatColour.GREEN),
                SeatOverride(hall, SeatId(2), SeatColour.YELLOW),
                SeatOverride(hall, SeatId(10), SeatColour.RED),
                SeatOverride(hall, SampleLayouts.yorckRowSixR, SeatColour.GREEN, isFavourite = true),
            ),
        )
    }

    /** The preloaded cinemas and halls without any visits, as after a fresh install. */
    fun freshInstallStore(): FakeStore = FakeStore(cinemas, halls.map { it.copy(layout = null) })
}

/** Invented but realistic layouts for screenshots and tests (see [SampleData]). */
object SampleLayouts {
    /**
     * A 10-row hall in three blocks split by two aisles, rows numbered 1–10 (the
     * default labels; Yorck numbers rows and seats): short front rows, a staggered
     * row 4 (half-seat offset), seat numbers restarting in every block, and a back
     * row with wheelchair spaces, companion seats and sofa places. 183 seats.
     */
    val splitHall: SeatLayout = SeatLayout.fromPatterns(
        "..SS|SSSSSSSSSSSS|SS..",
        ".SSS|SSSSSSSSSSSS|SSS.",
        "SSSS|SSSSSSSSSSSS|SSSS",
        "SSSS|SSSSSSSSSSS.|SSSS",
        "SSSS|SSSSSSSSSSSS|SSSS",
        "SSSS|SSSSSSSSSSSS|SSSS",
        "SSSS|SSSSSSSSSSSS|SSSS",
        "SSSS|SSSSSSSSSSSS|SSSS",
        ".SSS|SSSSSSSSSSSS|SSS.",
        "WC..|..FFFFFFFF..|..CW",
        firstSeatId = 1,
    ).let { layout ->
        (0 until layout.rowCount).fold(layout) { acc, row ->
            acc.updateRow(row) {
                it.copy(
                    numbering = SeatNumbering.RESTART_PER_BLOCK,
                    halfSeatOffset = row == 3,
                )
            }
        }
    }

    /**
     * Seat numbers as Yorck has them (spec §4.3), in a 7-row hall of 15 columns:
     * - row 1: wheelchair spaces at both ends, numbered like every seat (1 2 · 3–9 · 10 11);
     * - row 2: right to left (13 … 1);
     * - row 3: left to right from 3 (3 … 15);
     * - row 4: three blocks, right to left (13–11 | 10–4 | 3–1);
     * - row 5: three blocks, left to right, restarting per block;
     * - row 6: right to left, the wheelchair space at the right end labelled "R" (14 … 1 R);
     * - row 7: left to right, an "R" wheelchair space first, a companion seat last (R 1 … 14).
     * Seat ids run row-major from 1: row 6's "R" is seat 78, row 7's is seat 79.
     */
    val yorckNumbering: SeatLayout = SeatLayout.fromPatterns(
        ".WW.SSSSSSS.WW.",
        ".SSSSSSSSSSSSS.",
        ".SSSSSSSSSSSSS.",
        "SSS|SSSSSSS|SSS",
        "SSS|SSSSSSS|SSS",
        "SSSSSSSSSSSSSSW",
        "WSSSSSSSSSSSSSC",
        firstSeatId = 1,
    )
        .updateRow(1) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }
        .updateRow(2) { it.copy(blockStartNumbers = listOf(3)) }
        .updateRow(3) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }
        .updateRow(4) { it.copy(numbering = SeatNumbering.RESTART_PER_BLOCK) }
        .updateRow(5) { it.copy(numberingDirection = NumberingDirection.RIGHT_TO_LEFT) }
        .withFixedLabel(5, 14, "R")
        .withFixedLabel(6, 0, "R")

    /** [yorckNumbering]'s wheelchair space labelled "R" in row 6. */
    val yorckRowSixR: SeatId = SeatId(78)
}
