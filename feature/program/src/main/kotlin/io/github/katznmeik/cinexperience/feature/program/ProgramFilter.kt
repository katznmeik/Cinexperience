package io.github.katznmeik.cinexperience.feature.program

import io.github.katznmeik.cinexperience.core.program.Program
import io.github.katznmeik.cinexperience.core.program.Showing
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** The day filter of the Program tab (spec §6.9). */
enum class DayChoice(val label: String) {
    TODAY("Today"),
    TOMORROW("Tomorrow"),

    /** From today until Sunday (only today on a Sunday). */
    THIS_WEEK("This week"),

    /** The coming Saturday and Sunday; on a Saturday this weekend, on a Sunday only today. */
    WEEKEND("Weekend"),

    /** The dates in [ProgramFilter.dates]. */
    DATES("Dates"),
}

/**
 * The Program tab's filters (spec §6.9). Empty sets mean "any".
 *
 * @property cinemas seed keys of the app cinemas to show ([io.github.katznmeik.cinexperience.core.model.Cinema.seedKey]);
 *   empty = every cinema, including those Yorck has and the app doesn't know.
 * @property dates the chosen dates, used when [day] is [DayChoice.DATES].
 * @property from earliest start time (inclusive), `null` = any.
 * @property to latest start time (inclusive), `null` = any. Earlier than [from]
 *   means a range across midnight (e.g. 22:00–01:00).
 * @property versions and [events] together: a showing matches if it has any
 *   selected version or event label; both empty = every showing.
 * @property search part of the film title, ignoring case and accents.
 */
data class ProgramFilter(
    val cinemas: Set<String> = emptySet(),
    val day: DayChoice = DayChoice.TODAY,
    val dates: Set<LocalDate> = emptySet(),
    val from: LocalTime? = null,
    val to: LocalTime? = null,
    val versions: Set<ShowingVersion> = emptySet(),
    val events: Set<String> = emptySet(),
    val search: String = "",
) {
    /** True if nothing but the default day is set. */
    val isDefault: Boolean get() = this == ProgramFilter()

    /** The filter without the chosen dates before [today] (Today if none are left). */
    fun withoutPastDates(today: LocalDate): ProgramFilter {
        val left = dates.filterTo(sortedSetOf()) { !it.isBefore(today) }
        if (left.size == dates.size) return this
        return copy(dates = left, day = if (day == DayChoice.DATES && left.isEmpty()) DayChoice.TODAY else day)
    }
}

/** A filter saved under a [name] (spec §6.9, "Saved filters"). */
data class SavedFilter(val name: String, val filter: ProgramFilter)

/** Pure date and matching logic of the Program filters, with Berlin wall-clock dates ([io.github.katznmeik.cinexperience.core.program.ProgramTime]). */
object ProgramFilters {

    /** The dates [day] stands for, seen on [today]; [chosen] for [DayChoice.DATES]. */
    fun datesOf(day: DayChoice, chosen: Set<LocalDate>, today: LocalDate): Set<LocalDate> = when (day) {
        DayChoice.TODAY -> setOf(today)
        DayChoice.TOMORROW -> setOf(today.plusDays(1))
        DayChoice.THIS_WEEK -> datesBetween(today, today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)))
        DayChoice.WEEKEND -> when (today.dayOfWeek) {
            DayOfWeek.SUNDAY -> setOf(today)
            else -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).let { setOf(it, it.plusDays(1)) }
        }
        DayChoice.DATES -> chosen.filterTo(sortedSetOf()) { !it.isBefore(today) }
    }

    /** Every date from [first] to [last], both included. */
    fun datesBetween(first: LocalDate, last: LocalDate): Set<LocalDate> =
        generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toCollection(sortedSetOf())

    /** True if [time] lies in [from]–[to] (inclusive; either may be open; across midnight if [to] is earlier). */
    fun inTimeRange(time: LocalTime, from: LocalTime?, to: LocalTime?): Boolean = when {
        from == null && to == null -> true
        from == null -> !time.isAfter(to)
        to == null -> !time.isBefore(from)
        !from.isAfter(to) -> !time.isBefore(from) && !time.isAfter(to)
        else -> !time.isBefore(from) || !time.isAfter(to)
    }

    /** True if [showing] has one of [versions] or [events] (or both are empty). */
    fun matchesLabels(showing: Showing, versions: Set<ShowingVersion>, events: Set<String>): Boolean {
        if (versions.isEmpty() && events.isEmpty()) return true
        if (showing.versions.any { it in versions }) return true
        val wanted = events.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        return showing.eventLabels.any { it.lowercase(Locale.ROOT) in wanted }
    }

    /**
     * The film search: true if a title of [showing] ([ProgramLookup.searchableTitles])
     * contains [search], ignoring case, accents and surrounding spaces.
     */
    fun matchesSearch(program: Program, showing: Showing, search: String): Boolean {
        val needle = normalise(search)
        return needle.isEmpty() || ProgramLookup.searchableTitles(program, showing).any { normalise(it).contains(needle) }
    }

    private val MARKS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")

    private fun normalise(text: String): String =
        java.text.Normalizer.normalize(text.trim(), java.text.Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace(SPACES, " ")
            .lowercase(Locale.ROOT)

    /**
     * The showings of [program] that match [filter], upcoming at [now] and on a date
     * of the filter (seen on [today], Berlin time), sorted by start.
     */
    fun apply(program: Program, filter: ProgramFilter, now: Instant, today: LocalDate): List<Showing> {
        val dates = datesOf(filter.day, filter.dates, today)
        if (dates.isEmpty()) return emptyList()
        return program.showings.filter { showing ->
            showing.isUpcoming(now) &&
                showing.start.toLocalDate() in dates &&
                (filter.cinemas.isEmpty() || showing.cinema.seedKey in filter.cinemas) &&
                inTimeRange(showing.start.toLocalTime(), filter.from, filter.to) &&
                matchesLabels(showing, filter.versions, filter.events) &&
                matchesSearch(program, showing, filter.search)
        }
    }

    /** The event labels of [program]'s upcoming showings, sorted by name, without repeats (ignoring case). */
    fun eventLabels(program: Program?, now: Instant): List<String> =
        program?.showings.orEmpty()
            .filter { it.isUpcoming(now) }
            .flatMap { it.eventLabels }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedBy { it.lowercase(Locale.ROOT) }
}
