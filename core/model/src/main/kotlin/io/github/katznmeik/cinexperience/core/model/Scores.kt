package io.github.katznmeik.cinexperience.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The mean of [count] (≥ 1) ratings. Categories without any rating have no
 * [CategoryScore] at all: "unrated" is absence, never 0 (spec §5).
 */
data class CategoryScore(val average: Double, val count: Int) {
    init {
        require(count > 0) { "A score needs at least one rating" }
    }
}

/**
 * Scores of one hall (spec §5).
 *
 * @property categories category → score, only for categories with at least one rating.
 * @property overall mean of the category averages that have data; `null` if none.
 * @property visitCount number of visits, rated or not.
 */
data class HallScores(
    val categories: Map<HallCategory, CategoryScore>,
    val overall: Double?,
    val visitCount: Int,
) {
    operator fun get(category: HallCategory): CategoryScore? = categories[category]

    companion object {
        val EMPTY: HallScores = HallScores(emptyMap(), null, 0)
    }
}

/**
 * Scores of one cinema (spec §5).
 *
 * @property categories cinema category → score over all visits to all its halls.
 * @property halls hall id → that hall's scores, for every hall passed in.
 * @property overall mean of (cinema category averages with data + hall overall
 *   scores with data), each counted once; `null` if there is nothing to average.
 * @property visitCount number of visits across all halls.
 */
data class CinemaScores(
    val categories: Map<CinemaCategory, CategoryScore>,
    val halls: Map<Long, HallScores>,
    val overall: Double?,
    val visitCount: Int,
) {
    operator fun get(category: CinemaCategory): CategoryScore? = categories[category]
}

/** Mean of the non-null ratings, or `null` if there are none. */
fun categoryScore(ratings: Iterable<Rating?>): CategoryScore? {
    var sum = 0
    var count = 0
    for (rating in ratings) {
        if (rating != null) {
            sum += rating.value
            count++
        }
    }
    return if (count == 0) null else CategoryScore(sum.toDouble() / count, count)
}

/** Hall category scores and overall score from the visits of one hall (spec §5). */
fun hallScores(visits: List<Visit>): HallScores {
    val categories = HallCategory.entries
        .mapNotNull { category -> categoryScore(visits.map { it.hallRatings[category] })?.let { category to it } }
        .toMap()
    return HallScores(categories, meanOrNull(categories.values.map { it.average }), visits.size)
}

/**
 * Cinema scores (spec §5) from the visits of each of its halls. Pass every hall of
 * the cinema as a key, with an empty list for halls without visits.
 */
fun cinemaScores(visitsByHall: Map<Long, List<Visit>>): CinemaScores {
    val allVisits = visitsByHall.values.flatten()
    val categories = CinemaCategory.entries
        .mapNotNull { category -> categoryScore(allVisits.map { it.cinemaRatings[category] })?.let { category to it } }
        .toMap()
    val halls = visitsByHall.mapValues { (_, visits) -> hallScores(visits) }
    val overall = meanOrNull(categories.values.map { it.average } + halls.values.mapNotNull { it.overall })
    return CinemaScores(categories, halls, overall, allVisits.size)
}

private fun meanOrNull(values: List<Double>): Double? = if (values.isEmpty()) null else values.average()

/** Placeholder shown for unrated values (spec §5). */
const val NO_SCORE_TEXT: String = "–"

/**
 * [score] without floating-point noise: scores are means of means, so equal ones
 * can differ in their last bits (mean[2.2] is 2.2, mean[2.0, 2.0, 2.6] is
 * 2.1999999999999997, and 3.95 can be computed as 3.9499999999999997). Rounded to
 * 9 decimals, far below anything a mean of 1–5 ratings can tell apart.
 */
private fun withoutNoise(score: Double): BigDecimal = BigDecimal.valueOf(score).setScale(9, RoundingMode.HALF_EVEN)

/**
 * Formats a score with one decimal (half up, e.g. 4.25 → "4.3"), or [NO_SCORE_TEXT]
 * for `null`. The noise is removed first ([withoutNoise]), so an exact x.x5 never
 * lands just below the boundary.
 */
fun formatScore(score: Double?): String =
    score?.let { withoutNoise(it).setScale(1, RoundingMode.HALF_UP).toPlainString() } ?: NO_SCORE_TEXT

/**
 * The order of every score-sorted list (the cinema list's score sort, spec §6.1, and
 * the Rankings, spec §6.6): highest score first, unrated (`null`) last. Scores that
 * are mathematically equal compare as equal, whatever their floating-point noise
 * ([withoutNoise]), so each list's own tie-break decides between them, and a stable
 * sort keeps their input order.
 */
object ScoreOrder : Comparator<Double?> {
    override fun compare(a: Double?, b: Double?): Int = when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> withoutNoise(b).compareTo(withoutNoise(a))
    }
}
