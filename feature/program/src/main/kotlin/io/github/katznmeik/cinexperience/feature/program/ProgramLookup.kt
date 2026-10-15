package io.github.katznmeik.cinexperience.feature.program

import io.github.katznmeik.cinexperience.core.program.Film
import io.github.katznmeik.cinexperience.core.program.FilmKey
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.program.Program
import io.github.katznmeik.cinexperience.core.program.ProgramStatus
import io.github.katznmeik.cinexperience.core.program.Showing
import java.time.Instant
import java.time.LocalDateTime

/*
 * How this module reads core:program's model: films by key, a film's showings,
 * which titles the search sees, the end time of a showing and the download
 * errors. The screens go through these functions.
 */

internal object ProgramLookup {

    /** The key in a route ([FilmKind.name] and slug), `null` for an unknown kind or an invalid slug. */
    fun keyOf(kind: String, slug: String): FilmKey? {
        val filmKind = FilmKind.entries.firstOrNull { it.name == kind } ?: return null
        return runCatching { FilmKey(filmKind, slug) }.getOrNull()
    }

    /** The film (or special) [key] names in [program], `null` if it has none. */
    fun film(program: Program?, key: FilmKey): Film? = program?.film(key)

    /** The film (or special) that owns [showing]. */
    fun filmOf(program: Program, showing: Showing): Film = program.filmOf(showing)

    /** The upcoming showings of [key]: its own and, for a special, the ones it lists. */
    fun upcomingShowingsOf(program: Program, key: FilmKey, now: Instant): List<Showing> =
        program.showingsOf(key).filter { it.isUpcoming(now) }

    /** The specials (series, festivals) that also list [showing], for "Part of: …". */
    fun specialsOf(program: Program, showing: Showing): List<Film> = showing.specialKeys.mapNotNull(program::film)

    /** The titles the film search matches for [showing]: its film's and its specials' titles. */
    fun searchableTitles(program: Program, showing: Showing): List<String> =
        listOf(filmOf(program, showing).title) + specialsOf(program, showing).map { it.title }

    /** "Ends about" (spec §6.9): start + the film's runtime, `null` without a runtime. */
    fun endsAbout(showing: Showing, film: Film): LocalDateTime? = showing.endsAbout(film)
}

/** The message of a failed download for the Program tab; `null` when idle or downloading. */
fun downloadErrorMessage(status: ProgramStatus): String? = (status as? ProgramStatus.Failed)?.message
