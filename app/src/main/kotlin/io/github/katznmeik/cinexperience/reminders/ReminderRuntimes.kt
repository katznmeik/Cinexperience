package io.github.katznmeik.cinexperience.reminders

import io.github.katznmeik.cinexperience.core.model.VisitListItem
import io.github.katznmeik.cinexperience.core.program.Program
import io.github.katznmeik.cinexperience.core.program.Showing
import java.time.LocalDateTime
import java.util.Locale

/**
 * The runtimes of the visits' showings in the program already on the phone (spec
 * §11.3 rule 2, matched as in the ticket import, §11.2): a showing at the visit's
 * cinema (by seed key) with the same Berlin start. With several showings at that time
 * (different halls), those whose film has the visit's title count, if any do. A runtime
 * only when they agree on one. Pure; the reminders never download.
 */
internal object ReminderRuntimes {

    /**
     * The runtime in minutes by visit id, for the [visits] with a start time whose
     * cinema has a seed key in [seedKeys] (cinema id → seed key) and whose showing
     * [program] has; the others are left out.
     */
    fun of(program: Program?, visits: List<VisitListItem>, seedKeys: Map<Long, String>): Map<Long, Int> {
        program ?: return emptyMap()
        if (visits.none { it.visit.startTime != null }) return emptyMap()
        val showings: Map<Pair<String, LocalDateTime>, List<Showing>> = program.showings
            .filter { it.cinema.seedKey != null }
            .groupBy { it.cinema.seedKey!! to it.start }
        val runtimes = HashMap<Long, Int>()
        for (item in visits) {
            val startTime = item.visit.startTime ?: continue
            val seedKey = seedKeys[item.cinemaId] ?: continue
            val atThatTime = showings[seedKey to item.visit.date.atTime(startTime)] ?: continue
            val title = item.visit.filmTitle?.let(::normalised)
            val sameFilm = if (atThatTime.size > 1 && title != null) {
                atThatTime.filter { normalised(program.filmOf(it).title) == title }
            } else {
                emptyList()
            }
            val runtime = sameFilm.ifEmpty { atThatTime }.map { program.filmOf(it).runtimeMinutes }.distinct().singleOrNull()
            if (runtime != null) runtimes[item.visit.id] = runtime
        }
        return runtimes
    }

    private fun normalised(name: String): String = name.trim().split(WHITESPACE).joinToString(" ").lowercase(Locale.ROOT)

    private val WHITESPACE = Regex("\\s+")
}
