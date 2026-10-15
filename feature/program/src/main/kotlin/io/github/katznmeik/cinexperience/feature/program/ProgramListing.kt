package io.github.katznmeik.cinexperience.feature.program

import io.github.katznmeik.cinexperience.core.program.FilmKey
import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.program.Program
import io.github.katznmeik.cinexperience.core.program.Showing
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * An app cinema as the program shows it (spec §6.9, §11.1): joined with a showing's
 * cinema by its seed key.
 *
 * @property singleHall the cinema's only hall (Yorck's page has no hall; it is
 *   shown for cinemas with exactly one), `null` for cinemas with several.
 */
data class AppCinema(
    val id: Long,
    val seedKey: String,
    val name: String,
    val overallScore: Double?,
    val isFavourite: Boolean,
    val singleHall: String?,
)

/** The app's cinemas by seed key (only cinemas with one; user-added cinemas have none). */
class CinemaJoin(cinemas: List<CinemaDetail>) {
    private val bySeedKey: Map<String, AppCinema> = cinemas.mapNotNull { detail ->
        val key = detail.cinema.seedKey ?: return@mapNotNull null
        key to AppCinema(
            id = detail.cinema.id,
            seedKey = key,
            name = detail.cinema.name,
            overallScore = detail.scores.overall,
            isFavourite = detail.cinema.isFavourite,
            singleHall = detail.halls.singleOrNull()?.hall?.name,
        )
    }.toMap()

    /** The app cinema with [seedKey], `null` without one (shown with Yorck's name and no score). */
    operator fun get(seedKey: String?): AppCinema? = seedKey?.let(bySeedKey::get)

    /** Every app cinema that can appear in the program, by name. */
    val cinemas: List<AppCinema> = bySeedKey.values.sortedBy { it.name.lowercase() }

    companion object {
        val EMPTY = CinemaJoin(emptyList())
    }
}

/**
 * One showing row (spec §6.9, Results): everything it shows, already joined.
 *
 * @property cinemaName the app's name for a matched cinema, else Yorck's name.
 * @property cinema the matched app cinema (score, ★, single hall), `null` if none.
 * @property endsAbout start + runtime, `null` without a runtime.
 */
data class ShowingItem(
    val showingId: String,
    val film: FilmKey,
    val filmTitle: String,
    val start: LocalDateTime,
    val cinemaName: String,
    val cinema: AppCinema?,
    val versions: List<ShowingVersion>,
    val events: List<String>,
    val runtimeMinutes: Int?,
    val endsAbout: LocalDateTime?,
    /** Titles of the specials (series, festivals) that also list this showing. */
    val partOf: List<String> = emptyList(),
) {
    val hallName: String? get() = cinema?.singleHall
}

/** The showings of one day, sorted by start. */
data class ShowingDay(val date: LocalDate, val showings: List<ShowingItem>)

/** The row of [showing] in [program], joined with the app's cinemas. */
fun showingItem(program: Program, showing: Showing, join: CinemaJoin): ShowingItem {
    val film = ProgramLookup.filmOf(program, showing)
    val cinema = join[showing.cinema.seedKey]
    return ShowingItem(
        showingId = showing.id,
        film = film.key,
        filmTitle = film.title,
        start = showing.start,
        cinemaName = cinema?.name ?: showing.cinema.name,
        cinema = cinema,
        versions = showing.versions,
        events = showing.eventLabels,
        runtimeMinutes = film.runtimeMinutes,
        endsAbout = ProgramLookup.endsAbout(showing, film),
        partOf = ProgramLookup.specialsOf(program, showing).map { it.title },
    )
}

/** [showings] (sorted by start) as rows grouped by day. */
fun groupByDay(program: Program, showings: List<Showing>, join: CinemaJoin): List<ShowingDay> =
    showings.groupBy { it.start.toLocalDate() }
        .map { (date, list) -> ShowingDay(date, list.sortedBy { it.start }.map { showingItem(program, it, join) }) }
        .sortedBy { it.date }

/** The app's cinemas, then Yorck's cinemas without an app cinema (and selected ones gone from the program). */
internal fun cinemaChoices(join: CinemaJoin, program: Program?, filter: ProgramFilter): List<CinemaChoice> {
    val app = join.cinemas.map { CinemaChoice(it.seedKey, it.name) }
    val known = app.mapTo(HashSet()) { it.key }
    val yorck = program?.cinemas.orEmpty()
        .filter { join[it.seedKey] == null && it.key !in known }
        .map { CinemaChoice(it.key, it.name) }
    val listed = known + yorck.map { it.key }
    val missing = filter.cinemas.filter { it !in listed }.sorted().map { CinemaChoice(it, it) }
    return app + yorck.sortedBy { it.name.lowercase() } + missing
}
