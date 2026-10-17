package io.github.katznmeik.cinexperience.feature.rankings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.repository.Rankings
import io.github.katznmeik.cinexperience.core.data.repository.RankingsRepository
import io.github.katznmeik.cinexperience.core.model.CinemaCategory
import io.github.katznmeik.cinexperience.core.model.HallCategory
import io.github.katznmeik.cinexperience.core.model.NameOrder
import io.github.katznmeik.cinexperience.core.model.ScoreOrder
import io.github.katznmeik.cinexperience.core.ui.flow.asResult
import io.github.katznmeik.cinexperience.core.ui.text.countText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the Rankings tab ranks (spec §6.6). */
enum class RankingScope(val label: String) {
    HALLS("Halls"),
    CINEMAS("Cinemas"),
}

private const val OVERALL_LABEL = "Overall"

/**
 * Category chips for halls, in chip order; [category] `null` = overall. Labels are
 * the categories' display names, except the deliberately short "Steepness" chip
 * (display name "Seating steepness") so the scrolling chip row stays compact.
 */
enum class HallRankingCategory(val category: HallCategory?, shortLabel: String? = null) {
    OVERALL(null, OVERALL_LABEL),
    SOUND(HallCategory.SOUND),
    PICTURE(HallCategory.PICTURE),
    SCREEN_SIZE(HallCategory.SCREEN_SIZE),
    SEAT_COMFORT(HallCategory.SEAT_COMFORT),
    LEGROOM(HallCategory.LEGROOM),
    STEEPNESS(HallCategory.STEEPNESS, "Steepness"),
    ;

    val label: String = shortLabel ?: category!!.displayName
}

/** Category chips for cinemas, in chip order; [category] `null` = overall. Labels are the display names. */
enum class CinemaRankingCategory(val category: CinemaCategory?) {
    OVERALL(null),
    ATMOSPHERE(CinemaCategory.ATMOSPHERE),
    BAR_SNACKS(CinemaCategory.BAR_SNACKS),
    POPCORN(CinemaCategory.POPCORN),
    SEAT_COMFORT(CinemaCategory.SEAT_COMFORT),
    ;

    val label: String = category?.displayName ?: OVERALL_LABEL
}

/** A chip of the category row. */
data class CategoryChip(val label: String, val selected: Boolean)

/**
 * One row of the ranked list.
 *
 * @property id hall id or cinema id, depending on the scope.
 * @property position 1-based rank; `null` for unrated items (shown as "–").
 * @property score `null` = not rated in the chosen category.
 * @property ratingCount ratings behind [score]; for overall the rated visits (`ratedVisitCount` of the ranking), the first tie-breaker.
 */
data class RankedItem(
    val id: Long,
    val title: String,
    val subtitle: String,
    val score: Double?,
    val ratingCount: Int,
    val position: Int?,
)

data class RankingsUiState(
    val isLoading: Boolean = true,
    val scope: RankingScope = RankingScope.HALLS,
    val hallCategory: HallRankingCategory = HallRankingCategory.OVERALL,
    val cinemaCategory: CinemaRankingCategory = CinemaRankingCategory.OVERALL,
    val items: List<RankedItem> = emptyList(),
    /** Nothing has been rated yet (no hall and no cinema has an overall score). */
    val nothingRated: Boolean = false,
    val errorMessage: String? = null,
) {
    val chips: List<CategoryChip>
        get() = when (scope) {
            RankingScope.HALLS -> HallRankingCategory.entries.map { CategoryChip(it.label, it == hallCategory) }
            RankingScope.CINEMAS -> CinemaRankingCategory.entries.map { CategoryChip(it.label, it == cinemaCategory) }
        }
}

/**
 * Ranks [this]: highest score first ([ScoreOrder], like the cinema list:
 * mathematically equal scores tie); ties → more ratings first, then natural name
 * order ([NameOrder] on title, then subtitle); unrated items last in name order.
 * Rated items get positions 1..n, unrated ones none.
 */
fun List<RankedItem>.ranked(): List<RankedItem> {
    val byName = compareBy(NameOrder) { it: RankedItem -> it.title }.thenBy(NameOrder) { it.subtitle }
    val (rated, unrated) = partition { it.score != null }
    val sortedRated = rated.sortedWith(
        compareBy<RankedItem, Double?>(ScoreOrder) { it.score }
            .thenByDescending { it.ratingCount }
            .then(byName),
    )
    return sortedRated.mapIndexed { i, item -> item.copy(position = i + 1) } +
        unrated.sortedWith(byName).map { it.copy(position = null) }
}

/** The ranked rows of [rankings] for the given scope and category. */
fun rankedItems(
    rankings: Rankings,
    scope: RankingScope,
    hallCategory: HallRankingCategory,
    cinemaCategory: CinemaRankingCategory,
): List<RankedItem> = when (scope) {
    RankingScope.HALLS -> rankings.halls.map { r ->
        val score = hallCategory.category?.let { r.scores[it] }
        RankedItem(
            id = r.hall.id,
            title = r.hall.name,
            subtitle = r.cinemaName,
            score = if (hallCategory.category == null) r.scores.overall else score?.average,
            ratingCount = if (hallCategory.category == null) r.ratedVisitCount else score?.count ?: 0,
            position = null,
        )
    }
    RankingScope.CINEMAS -> rankings.cinemas.map { r ->
        val score = cinemaCategory.category?.let { r.scores[it] }
        RankedItem(
            id = r.cinema.id,
            title = r.cinema.name,
            subtitle = "${countText(r.hallCount, "hall")} · ${countText(r.scores.visitCount, "visit")}",
            score = if (cinemaCategory.category == null) r.scores.overall else score?.average,
            ratingCount = if (cinemaCategory.category == null) r.ratedVisitCount else score?.count ?: 0,
            position = null,
        )
    }
}.ranked()

/** Rankings tab (spec §6.6). Default Halls + Overall; the selection is not persisted. */
class RankingsViewModel(rankingsRepository: RankingsRepository) : ViewModel() {

    private val selection = MutableStateFlow(RankingsUiState(isLoading = false))

    private val rankings = rankingsRepository.observeRankings().asResult()

    val uiState: StateFlow<RankingsUiState> = combine(rankings, selection) { result, sel ->
        val data = result.getOrNull()
        sel.copy(
            isLoading = false,
            items = data?.let { rankedItems(it, sel.scope, sel.hallCategory, sel.cinemaCategory) }.orEmpty(),
            nothingRated = data != null &&
                data.halls.none { it.scores.overall != null } &&
                data.cinemas.none { it.scores.overall != null },
            errorMessage = if (result.isFailure) LOAD_FAILED else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RankingsUiState())

    fun setScope(scope: RankingScope) {
        selection.value = selection.value.copy(scope = scope)
    }

    /** Selects the chip at [index] of the current scope's category row. */
    fun selectCategory(index: Int) {
        val current = selection.value
        selection.value = when (current.scope) {
            RankingScope.HALLS -> current.copy(hallCategory = HallRankingCategory.entries[index])
            RankingScope.CINEMAS -> current.copy(cinemaCategory = CinemaRankingCategory.entries[index])
        }
    }

    companion object {
        const val LOAD_FAILED = "Couldn't load the rankings."

        fun factory(rankingsRepository: RankingsRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { RankingsViewModel(rankingsRepository) }
        }
    }
}
