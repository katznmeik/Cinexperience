package io.github.katznmeik.cinexperience.core.data.repository

import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.CinemaScores
import io.github.katznmeik.cinexperience.core.model.Hall
import io.github.katznmeik.cinexperience.core.model.HallScores
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One hall of the Rankings tab (spec §6.6) with its cinema's name and its scores. */
data class HallRanking(
    val hall: Hall,
    val cinemaName: String,
    val scores: HallScores,
)

/** One cinema of the Rankings tab (spec §6.6) with its scores and hall count. */
data class CinemaRanking(
    val cinema: Cinema,
    val scores: CinemaScores,
    val hallCount: Int,
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
 * the `hallScores` of every hall. Works with the Room repository and with the
 * in-memory fakes alike.
 */
class CinemaRankingsRepository(private val cinemaRepository: CinemaRepository) : RankingsRepository {
    override fun observeRankings(): Flow<Rankings> = cinemaRepository.observeCinemaDetails().map { details ->
        Rankings(
            halls = details.flatMap { detail ->
                detail.halls.map { summary ->
                    HallRanking(
                        hall = summary.hall,
                        cinemaName = detail.cinema.name,
                        scores = detail.scores.halls[summary.hall.id] ?: HallScores.EMPTY,
                    )
                }
            },
            cinemas = details.map { CinemaRanking(it.cinema, it.scores, it.halls.size) },
        )
    }
}
