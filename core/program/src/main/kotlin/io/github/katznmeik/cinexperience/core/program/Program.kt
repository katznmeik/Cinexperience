package io.github.katznmeik.cinexperience.core.program

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Yorck's program as downloaded from www.yorck.de (spec §11.1): every film and
 * every showing of all Yorck cinemas. Read-only; built by [ProgramPageParser]
 * from the page and stored by the cache in the app's own format.
 *
 * Film slugs and showing IDs are unique, and every showing's [Showing.filmSlug]
 * names one of [films]. [showings] are sorted by start time.
 *
 * @property fetchedAt when the page was downloaded ("as of", spec §6.9).
 */
data class Program(
    val fetchedAt: Instant,
    val films: List<Film>,
    val showings: List<Showing>,
) {
    private val filmsBySlug: Map<String, Film> = films.associateBy { it.slug }

    init {
        require(filmsBySlug.size == films.size) { "Film slugs must be unique" }
        require(showings.mapTo(HashSet()) { it.id }.size == showings.size) { "Showing IDs must be unique" }
        require(showings.all { it.filmSlug in filmsBySlug }) { "Every showing must belong to one of the films" }
    }

    /** The film with [slug], `null` if the program has none. */
    fun film(slug: String): Film? = filmsBySlug[slug]

    /** The film of [showing]. */
    fun filmOf(showing: Showing): Film = filmsBySlug.getValue(showing.filmSlug)
}

/** Whether an entry of the program is one of Yorck's films or one of its specials (series, festivals, events). */
enum class FilmKind {
    /** From the page's film list; its page is `https://www.yorck.de/en/films/<slug>`. */
    FILM,

    /** From the page's specials (film series, festivals, special screenings); page `…/en/specials/<slug>`. */
    SPECIAL,
}

/**
 * A film (or special) of the program. All texts are cleaned plain text.
 *
 * @property slug the film's page name on yorck.de, e.g. `the-quiet-hour`; unique in a [Program].
 * @property genre Yorck's main label, e.g. "Drama".
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
}

/**
 * One showing. Yorck's page has no hall (spec §11.1).
 *
 * @property id Yorck's showing ID, e.g. `1001-89886` (digits-dash-digits); used for
 *   the booking link ([ProgramLinks.showing]).
 * @property start Berlin wall-clock start time, as shown on yorck.de ([ProgramTime.ZONE]).
 * @property versions language versions (usually one), in Yorck's order.
 * @property eventLabels display texts of the other labels, e.g. "Sneak", "Kids".
 */
data class Showing(
    val id: String,
    val filmSlug: String,
    val start: LocalDateTime,
    val cinema: ProgramCinema,
    val versions: List<ShowingVersion> = emptyList(),
    val eventLabels: List<String> = emptyList(),
) {
    init {
        require(ProgramRules.isValidShowingId(id)) { "Invalid showing ID" }
        require(ProgramRules.isValidSlug(filmSlug)) { "Invalid film slug" }
        require(eventLabels.all { ProgramRules.isValidText(it, ProgramRules.MAX_EVENT_LABEL_LENGTH) }) { "Invalid event label" }
    }

    /** The moment the showing starts. */
    val startInstant: Instant get() = start.atZone(ProgramTime.ZONE).toInstant()

    /** True if the showing has not started at [now] (past showings are hidden, spec §6.9). */
    fun isUpcoming(now: Instant): Boolean = startInstant.isAfter(now)
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

/** Times of the program: every start time is Berlin wall-clock time. */
object ProgramTime {
    val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    /** The current Berlin wall-clock time. */
    fun now(clock: Clock): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZONE)
}
