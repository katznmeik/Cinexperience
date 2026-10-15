package io.github.katznmeik.cinexperience.core.testing

import io.github.katznmeik.cinexperience.core.program.Film
import io.github.katznmeik.cinexperience.core.program.FilmKey
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.program.Program
import io.github.katznmeik.cinexperience.core.program.ProgramCinema
import io.github.katznmeik.cinexperience.core.program.Showing
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Builds a [Program] for tests and screenshots:
 *
 * ```
 * val program = buildProgram {
 *     val series = special("Autumn Classics")
 *     film("The Quiet Hour", runtimeMinutes = 104) {
 *         showing("2026-10-15T17:15", "delphi LUX", ShowingVersion.OMU, alsoIn = listOf(series))
 *     }
 * }
 * ```
 *
 * Cinemas are given by Yorck's name and matched like real data
 * ([ProgramCinema.fromYorckName]); showing IDs are numbered `1001-1`, `1001-2`, …
 * unless given. Showings are sorted by start like the parser does.
 */
class ProgramBuilder internal constructor(private val fetchedAt: Instant) {
    private val films = mutableListOf<Film>()
    private val showings = mutableListOf<Showing>()
    private var nextShowing = 1

    fun film(
        title: String,
        slug: String = slugOf(title),
        kind: FilmKind = FilmKind.FILM,
        tagline: String? = null,
        genre: String? = null,
        runtimeMinutes: Int? = null,
        fsk: Int? = null,
        releaseDate: LocalDate? = null,
        showings: FilmShowings.() -> Unit = {},
    ): Film {
        val film = Film(slug, title, kind, tagline, genre, runtimeMinutes, fsk, releaseDate)
        films += film
        FilmShowings(film).showings()
        return film
    }

    /** A special (film series, festival, special screening): a [film] of kind [FilmKind.SPECIAL]. */
    fun special(
        title: String,
        slug: String = slugOf(title),
        tagline: String? = null,
        showings: FilmShowings.() -> Unit = {},
    ): Film = film(title, slug, FilmKind.SPECIAL, tagline, showings = showings)

    inner class FilmShowings internal constructor(private val film: Film) {
        /**
         * A showing owned by this film; [start] is Berlin time such as `2026-10-15T17:15`.
         * [alsoIn] are specials that list it too ([Showing.specialKeys]).
         */
        fun showing(
            start: String,
            cinema: String,
            vararg versions: ShowingVersion,
            eventLabels: List<String> = emptyList(),
            alsoIn: List<Film> = emptyList(),
            id: String = "1001-${nextShowing++}",
        ): Showing {
            val showing = Showing(
                id = id,
                filmKey = film.key,
                start = LocalDateTime.parse(start),
                cinema = ProgramCinema.fromYorckName(cinema),
                versions = versions.toList(),
                eventLabels = eventLabels,
                specialKeys = alsoIn.map(Film::key),
            )
            this@ProgramBuilder.showings += showing
            return showing
        }
    }

    internal fun build(): Program = Program(
        fetchedAt = fetchedAt,
        films = films.toList(),
        showings = showings.sortedWith(compareBy<Showing>({ it.start }, { it.cinema.name }, { it.id })),
    )

    private companion object {
        private val NON_SLUG = Regex("[^a-z0-9]+")

        fun slugOf(title: String): String = title.lowercase(Locale.ROOT).replace(NON_SLUG, "-").trim('-')
    }
}

/** A [Program] downloaded at [fetchedAt], built by [block] (see [ProgramBuilder]). */
fun buildProgram(fetchedAt: Instant = SampleProgram.FETCHED_AT, block: ProgramBuilder.() -> Unit): Program =
    ProgramBuilder(fetchedAt).apply(block).build()

/**
 * An illustrative program with INVENTED films (titles, taglines, IDs), around
 * Thursday 15 October 2026, at real Yorck cinemas under Yorck's names (e.g.
 * "Kino International", and "Blauer Stern " with Yorck's trailing space), all
 * matched to the app's cinemas. It has a film series that only lists showings of
 * a film ([AUTUMN_CLASSICS]) and a special with its own showing. Never used by the app.
 */
object SampleProgram {
    /** 15 Oct 2026, 10:00 in Berlin. */
    val FETCHED_AT: Instant = Instant.parse("2026-10-15T08:00:00Z")

    /** A film series whose showings all belong to films. */
    val AUTUMN_CLASSICS: FilmKey = FilmKey(FilmKind.SPECIAL, "autumn-classics")

    /** A special with a showing of its own. */
    val MIDNIGHT_SURPRISE: FilmKey = FilmKey(FilmKind.SPECIAL, "midnight-surprise-screening")

    fun program(fetchedAt: Instant = FETCHED_AT): Program = buildProgram(fetchedAt) {
        val classics = special("Autumn Classics", tagline = "Old favourites on the big screen.")
        film(
            "The Quiet Projectionist",
            tagline = "A night shift that never ends.",
            genre = "Drama",
            runtimeMinutes = 118,
            fsk = 12,
            releaseDate = LocalDate.of(2026, 10, 1),
        ) {
            showing("2026-10-15T16:30", "delphi LUX", ShowingVersion.OMU)
            showing("2026-10-15T20:45", "Kant Kino", ShowingVersion.OV, alsoIn = listOf(classics))
            showing("2026-10-16T17:15", "delphi LUX", ShowingVersion.OMU)
            showing("2026-10-17T14:00", "Kino International", ShowingVersion.DF)
        }
        film(
            "Neon Harbour",
            tagline = "Every light hides a secret.",
            genre = "Thriller",
            runtimeMinutes = 97,
            fsk = 16,
            releaseDate = LocalDate.of(2026, 9, 24),
        ) {
            showing("2026-10-15T18:00", "Rollberg", ShowingVersion.OMEU)
            showing("2026-10-15T21:30", "Babylon Kreuzberg", ShowingVersion.OMU, eventLabels = listOf("Premiere"))
            showing("2026-10-18T19:00", "Blauer Stern ", ShowingVersion.DF, alsoIn = listOf(classics))
        }
        film(
            "Paper Kites",
            genre = "Animation",
            runtimeMinutes = 84,
            fsk = 0,
            releaseDate = LocalDate.of(2026, 10, 8),
        ) {
            showing("2026-10-17T11:00", "Filmtheater am Friedrichshain", ShowingVersion.DF, eventLabels = listOf("Kids"))
            showing("2026-10-18T11:00", "Capitol Dahlem", ShowingVersion.DF, eventLabels = listOf("Kids"))
        }
        special("Midnight Surprise Screening", tagline = "You won't know until the lights go down.") {
            showing("2026-10-19T22:00", "Odeon", ShowingVersion.OV, eventLabels = listOf("Sneak"))
        }
    }
}
