package io.github.katznmeik.cinexperience.core.data.repository

import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.CinemaRatings
import io.github.katznmeik.cinexperience.core.model.CinemaScores
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.HallRatings
import io.github.katznmeik.cinexperience.core.model.HallScores
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** One hall of the Rankings tab (spec §6.6) with its cinema's name and its scores. */
data class HallRanking(
    val hall: Hall,
    val cinemaName: String,
    val scores: HallScores,
    /** Visits with at least one of the six hall ratings (the ratings behind the overall score). */
    val ratedVisitCount: Int,
)

/** One cinema of the Rankings tab (spec §6.6) with its scores and hall count. */
data class CinemaRanking(
    val cinema: Cinema,
    val scores: CinemaScores,
    val hallCount: Int,
    /**
     * Visits with at least one cinema or hall rating. The cinema overall score
     * averages cinema category scores and hall overall scores, so both count.
     */
    val ratedVisitCount: Int,
)

/** Everything the Rankings tab ranks; cinemas by name, halls by cinema then hall name. */
data class Rankings(
    val halls: List<HallRanking>,
    val cinemas: List<CinemaRanking>,
)

interface RankingsRepository {
    /** Every hall and every cinema with scores computed by `hallScores` / `cinemaScores`. */
    fun observeRankings(): Flow<Rankings>
}

/**
 * [RankingsRepository] on top of [CinemaRepository.observeCinemaDetails], whose
 * `CinemaDetail.scores` already holds the `cinemaScores` of each cinema including
 * the `hallScores` of every hall, and [VisitRepository.observeAllVisits] for the
 * rated-visit counts. Works with the Room repository and the in-memory fakes alike.
 */
class CinemaRankingsRepository(
    private val cinemaRepository: CinemaRepository,
    private val visitRepository: VisitRepository,
) : RankingsRepository {
    override fun observeRankings(): Flow<Rankings> =
        combine(cinemaRepository.observeCinemaDetails(), visitRepository.observeAllVisits()) { details, visits ->
            val hallRated = visits.filter { it.visit.hallRatings != HallRatings.NONE }
                .groupingBy { it.visit.hallId }.eachCount()
            val cinemaRated = visits
                .filter { it.visit.hallRatings != HallRatings.NONE || it.visit.cinemaRatings != CinemaRatings.NONE }
                .groupingBy { it.cinemaId }.eachCount()
            Rankings(
                halls = details.flatMap { detail ->
                    detail.halls.map { summary ->
                        HallRanking(
                            hall = summary.hall,
                            cinemaName = detail.cinema.name,
                            scores = detail.scores.halls[summary.hall.id] ?: HallScores.EMPTY,
                            ratedVisitCount = hallRated[summary.hall.id] ?: 0,
                        )
                    }
                },
                cinemas = details.map {
                    CinemaRanking(it.cinema, it.scores, it.halls.size, cinemaRated[it.cinema.id] ?: 0)
                },
            )
        }
}
