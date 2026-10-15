package io.github.katznmeik.cinexperience.feature.program

import io.github.katznmeik.cinexperience.core.program.Film
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.program.Program
import io.github.katznmeik.cinexperience.core.program.ProgramError
import io.github.katznmeik.cinexperience.core.program.ProgramStatus
import io.github.katznmeik.cinexperience.core.program.Showing
import java.time.LocalDateTime

/*
 * The few places where this module reads core:program's model in a way that may
 * change: how a film is identified, which texts the title search sees, the end
 * time of a showing and which failures are download errors. Everything else goes
 * through these functions, so such a change stays here.
 */

/** Identifies a film or special of the program; the film page's route carries it. */
data class FilmId(val kind: FilmKind, val slug: String) {
    companion object {
        /** The id stored in a route ([FilmKind.name] and slug), `null` for an unknown kind. */
        fun of(kind: String, slug: String): FilmId? = FilmKind.entries.firstOrNull { it.name == kind }?.let { FilmId(it, slug) }
    }
}

internal object ProgramLookup {

    /** The id of [film]. */
    fun idOf(film: Film): FilmId = FilmId(film.kind, film.slug)

    /** The film [id] names in [program], `null` if it has none. */
    fun film(program: Program?, id: FilmId): Film? = program?.film(id.slug)?.takeIf { it.kind == id.kind }

    /** The film (or special) that owns [showing]. */
    fun filmOf(program: Program, showing: Showing): Film = program.filmOf(showing)

    /** True if [showing] belongs to the film [id]. */
    fun belongsTo(program: Program, showing: Showing, id: FilmId): Boolean = idOf(filmOf(program, showing)) == id

    /** The titles the film search matches for [showing]: its film's title. */
    fun searchableTitles(program: Program, showing: Showing): List<String> = listOf(filmOf(program, showing).title)

    /** "Ends about" (spec §6.9): start + the film's runtime, `null` without a runtime. */
    fun endsAbout(showing: Showing, film: Film): LocalDateTime? =
        film.runtimeMinutes?.let { showing.start.plusMinutes(it.toLong()) }
}

/**
 * The message of a failed download, for the Program tab; `null` when idle,
 * downloading, or when the failure was a Settings action (shown there instead).
 */
fun downloadErrorMessage(status: ProgramStatus): String? {
    val failed = status as? ProgramStatus.Failed ?: return null
    return if (failed.error in SETTINGS_ERRORS) null else failed.message
}

/** Failures of the Settings actions (switch, delete), not of a download. */
internal val SETTINGS_ERRORS: Set<ProgramError> = setOf(ProgramError.SETTING_NOT_SAVED, ProgramError.DELETE_FAILED)
