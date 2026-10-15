package io.github.katznmeik.cinexperience.core.program

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Yorck's program as downloaded from www.yorck.de (spec §11.1): every film,
 * every special (film series, festivals, special screenings) and every showing
 * of all Yorck cinemas. Read-only; built by [ProgramPageParser] from the page
 * and stored by the cache in the app's own format. Filtering is the UI's job;
 * these helpers only look things up.
 *
 * Guarantees (checked in `init`): film keys and showing IDs are unique, every
 * showing's [Showing.filmKey] and [Showing.specialKeys] name films of this
 * program, and [showings] are sorted by start time.
 *
 * @property fetchedAt when the page was downloaded ("as of", spec §6.9).
 */
data class Program(
    val fetchedAt: Instant,
    val films: List<Film>,
    val showings: List<Showing>,
) {
    private val filmsByKey: Map<FilmKey, Film> = films.associateBy { it.key }

    init {
        require(filmsByKey.size == films.size) { "Film keys must be unique" }
        require(showings.mapTo(HashSet()) { it.id }.size == showings.size) { "Showing IDs must be unique" }
        require(showings.all { it.filmKey in filmsByKey && it.specialKeys.all { key -> key in filmsByKey } }) {
            "Every showing must belong to films of the program"
        }
        require(showings.zipWithNext().all { (a, b) -> !b.start.isBefore(a.start) }) { "Showings must be sorted by start" }
    }

    /** The film (or special) with [key], `null` if the program has none. */
    fun film(key: FilmKey): Film? = filmsByKey[key]

    /** The film that owns [showing]. */
    fun filmOf(showing: Showing): Film = filmsByKey.getValue(showing.filmKey)

    /**
     * The showings of the film or special [key], by start time: the ones it owns
     * and, for a special, the ones owned by a film that the special lists too
     * ([Showing.specialKeys]).
     */
    fun showingsOf(key: FilmKey): List<Showing> = showings.filter { it.filmKey == key || key in it.specialKeys }

    /** The showings that have not started at [now] (past showings are hidden, spec §6.9). */
    fun upcoming(now: Instant): List<Showing> = showings.filter { it.isUpcoming(now) }

    /** Every cinema with showings, once each (by [ProgramCinema.key]), sorted by name. */
    val cinemas: List<ProgramCinema> by lazy {
        showings.map { it.cinema }.distinctBy { it.key }.sortedBy { it.name.lowercase() }
    }

    /** Every event label of the program, once each, sorted. */
    val eventLabels: List<String> by lazy {
        showings.flatMap { it.eventLabels }.distinct().sortedBy { it.lowercase() }
    }

    /**
     * "Now showing" at the app cinema [seedKey] (spec §6.2): its upcoming showings
     * from [now] to the end of the [days]-th day (today counts as day 1), by Berlin
     * date, each day sorted by start. Days without showings are left out.
     */
    fun nowShowing(seedKey: String, now: Instant, days: Int = 7): Map<LocalDate, List<Showing>> {
        require(days >= 1) { "days must be at least 1" }
        val lastDay = LocalDateTime.ofInstant(now, ProgramTime.ZONE).toLocalDate().plusDays((days - 1).toLong())
        return upcoming(now)
            .filter { it.cinema.seedKey == seedKey && !it.start.toLocalDate().isAfter(lastDay) }
            .groupBy { it.start.toLocalDate() }
    }
}

/** Whether a program entry is one of Yorck's films or one of its specials (series, festivals, events). */
enum class FilmKind {
    /** From the page's film list; its page is `https://www.yorck.de/en/films/<slug>`. */
    FILM,

    /** From the page's specials (film series, festivals, special screenings); page `…/en/specials/<slug>`. */
    SPECIAL,
}

/**
 * Identifies a film or special in a [Program]. Films and specials have separate
 * page names on yorck.de, so a special may share its [slug] with a film.
 */
data class FilmKey(val kind: FilmKind, val slug: String) {
    init {
        require(ProgramRules.isValidSlug(slug)) { "Invalid slug" }
    }
}

/**
 * A film (or special) of the program. All texts are cleaned plain text.
 *
 * @property slug the page name on yorck.de, e.g. `the-quiet-hour`; unique per [kind].
 * @property genre Yorck's main label, e.g. "Drama" (specials have none).
 * @property fsk German age rating: 0, 6, 12, 16 or 18.
 */
data class Film(
    val slug: String,
    val title: String,
    val kind: FilmKind = FilmKind.FILM,
    val tagline: String? = null,
    val genre: String? = null,
    val runtimeMinutes: Int? = null,
    val fsk: Int? = null,
    val releaseDate: LocalDate? = null,
) {
    init {
        require(ProgramRules.isValidSlug(slug)) { "Invalid slug" }
        require(ProgramRules.isValidText(title, ProgramRules.MAX_TITLE_LENGTH)) { "Invalid title" }
        require(tagline == null || ProgramRules.isValidText(tagline, ProgramRules.MAX_TAGLINE_LENGTH)) { "Invalid tagline" }
        require(genre == null || ProgramRules.isValidText(genre, ProgramRules.MAX_GENRE_LENGTH)) { "Invalid genre" }
        require(runtimeMinutes == null || runtimeMinutes in ProgramRules.RUNTIME_MINUTES) { "Invalid runtime" }
        require(fsk == null || fsk in ProgramRules.FSK_RATINGS) { "Invalid FSK rating" }
    }

    val key: FilmKey get() = FilmKey(kind, slug)
}

/**
 * One showing. Yorck's page has no hall (spec §11.1).
 *
 * @property id Yorck's showing ID, e.g. `1001-89886` (digits-dash-digits); used for
 *   the booking link ([ProgramLinks.showing]).
 * @property filmKey the film that owns the showing. A showing that Yorck lists under
 *   a film and under specials belongs to the film.
 * @property start Berlin wall-clock start time, as shown on yorck.de (see [ProgramTime]).
 * @property versions language versions (usually one), in Yorck's order.
 * @property eventLabels display texts of the other labels, e.g. "Sneak", "Kids".
 * @property specialKeys the specials that list this showing too (a festival or film
 *   series showing a film); empty for most showings.
 */
data class Showing(
    val id: String,
    val filmKey: FilmKey,
    val start: LocalDateTime,
    val cinema: ProgramCinema,
    val versions: List<ShowingVersion> = emptyList(),
    val eventLabels: List<String> = emptyList(),
    val specialKeys: List<FilmKey> = emptyList(),
) {
    init {
        require(ProgramRules.isValidShowingId(id)) { "Invalid showing ID" }
        require(eventLabels.all { ProgramRules.isValidText(it, ProgramRules.MAX_EVENT_LABEL_LENGTH) }) { "Invalid event label" }
        require(specialKeys.all { it.kind == FilmKind.SPECIAL } && specialKeys.distinct().size == specialKeys.size && filmKey !in specialKeys) {
            "Invalid special keys"
        }
    }

    /** The moment the showing starts ([ProgramTime.instantOf]). */
    val startInstant: Instant get() = ProgramTime.instantOf(start)

    /** True if the showing has not started at [now]. */
    fun isUpcoming(now: Instant): Boolean = startInstant.isAfter(now)

    /**
     * "Ends about" (spec §6.9): the Berlin wall-clock time [film]'s runtime after
     * the start, counted on the real timeline, so a showing across a clock change
     * ends at the right local time (01:30 + 120 min on 25 Oct 2026 ends at 02:30).
     * `null` if the runtime is unknown.
     */
    fun endsAbout(film: Film?): LocalDateTime? {
        val runtime = film?.runtimeMinutes ?: return null
        return ProgramTime.zoned(start).plusMinutes(runtime.toLong()).toLocalDateTime()
    }
}

/**
 * The cinema of a showing.
 *
 * @property name Yorck's name for display, e.g. "Kino International".
 * @property seedKey the matching preloaded app cinema ([io.github.katznmeik.cinexperience.core.model.Cinema.seedKey]),
 *   from the fixed table in [YorckCinemas]; `null` for a cinema the table doesn't
 *   know (shown with Yorck's name and no score, spec §11.1).
 */
data class ProgramCinema(val name: String, val seedKey: String?) {
    init {
        require(ProgramRules.isValidText(name, ProgramRules.MAX_CINEMA_NAME_LENGTH)) { "Invalid cinema name" }
    }

    /** A stable key for filters and lists: the seed key, or Yorck's name for an unmatched cinema. */
    val key: String get() = seedKey ?: name

    companion object {
        /**
         * The cinema called [yorckName] on yorck.de, matched with [YorckCinemas]; the
         * name is cleaned (e.g. Yorck's "Blauer Stern " loses its trailing space).
         *
         * @throws IllegalArgumentException if nothing (or more than 80 characters) remains.
         */
        fun fromYorckName(yorckName: String): ProgramCinema {
            val name = ProgramText.clean(yorckName)
            return ProgramCinema(name, YorckCinemas.seedKeyFor(name))
        }
    }
}

/**
 * A showing's language version (spec §6.9).
 *
 * @property label Yorck's label, also shown in the app.
 * @property description what it means, for filters and screen readers.
 */
enum class ShowingVersion(val label: String, val description: String) {
    DF("DF", "German"),
    OMU("OmU", "German subtitles"),
    OMEU("OmeU", "English subtitles"),
    OV("OV", "Original version"),
    ;

    companion object {
        /** The version labelled [label] (ignoring case and surrounding spaces), `null` if it is none. */
        fun fromLabel(label: String): ShowingVersion? =
            entries.firstOrNull { it.label.equals(label.trim(), ignoreCase = true) }
    }
}

/**
 * Times of the program. Every start time is a Berlin wall-clock time, kept as
 * shown on yorck.de. Where a moment is needed (upcoming, "ends about"), the
 * wall-clock time is placed in [ZONE] by the java.time rule:
 *
 * - a time in the spring gap that doesn't exist (29 Mar 2026, 02:00–02:59) is
 *   moved forward by the gap's length (02:30 → 03:30 summer time);
 * - a time in the autumn overlap that exists twice (25 Oct 2026, 02:00–02:59) is
 *   taken as the earlier one, still summer time (02:30 = 00:30 UTC).
 *
 * Yorck's offsets can't disambiguate: the page labels every time `+01:00`.
 */
object ProgramTime {
    val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    /** The current Berlin wall-clock time. */
    fun now(clock: Clock): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZONE)

    /** [wallClock] in Berlin, by the rule above. */
    fun zoned(wallClock: LocalDateTime): ZonedDateTime = wallClock.atZone(ZONE)

    /** The moment of the Berlin wall-clock time [wallClock], by the rule above. */
    fun instantOf(wallClock: LocalDateTime): Instant = zoned(wallClock).toInstant()
}
