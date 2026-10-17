package io.github.katznmeik.cinexperience.feature.program

import io.github.katznmeik.cinexperience.core.model.formatScore
import io.github.katznmeik.cinexperience.core.program.Film
import io.github.katznmeik.cinexperience.core.program.ProgramTime
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import io.github.katznmeik.cinexperience.core.ui.text.dateText
import io.github.katznmeik.cinexperience.core.ui.text.dateTimeText
import io.github.katznmeik.cinexperience.core.ui.text.dayText
import io.github.katznmeik.cinexperience.core.ui.text.isolate
import io.github.katznmeik.cinexperience.core.ui.text.timeText
import java.time.Instant
import java.time.LocalTime

/** "As of 15 Oct 2026, 10:00" (Berlin time, like the program). */
internal fun asOfText(instant: Instant): String = "As of ${dateTimeText(instant, ProgramTime.ZONE)}"

/** "118 min · ends about 18:28", or just one of them, or `null`. */
internal fun runtimeText(item: ShowingItem): String? = listOfNotNull(
    item.runtimeMinutes?.let { "$it min" },
    item.endsAbout?.let { "ends about ${timeText(it)}" },
).joinToString(" · ").ifEmpty { null }

/** The cinema's score as shown next to its name: "4.4", "–" when unrated, `null` for a cinema the app doesn't know. */
internal fun cinemaScoreText(item: ShowingItem): String? = item.cinema?.let { formatScore(it.overallScore) }

/** The score for screen readers: "score 4.4" / "not rated yet". */
internal fun spokenCinemaScore(cinema: AppCinema): String =
    cinema.overallScore?.let { "score ${formatScore(it)}" } ?: "not rated yet"

/** A version chip's spoken text: "OmU, German subtitles". */
internal fun versionDescription(version: ShowingVersion): String = "${version.label}, ${version.description}"

/** Film facts: "Drama · 118 min · FSK 12 · Released 1 Oct 2026". */
internal fun filmFactsText(film: Film): String? = listOfNotNull(
    film.genre?.let(::isolate),
    film.runtimeMinutes?.let { "$it min" },
    film.fsk?.let { "FSK $it" },
    film.releaseDate?.let { "Released ${dateText(it)}" },
).joinToString(" · ").ifEmpty { null }

/** Summary of the filters other than day and search: "2 cinemas · 16:00–17:30 · OmU, OV". */
internal fun filterSummary(filter: ProgramFilter, cinemaNames: Map<String, String>): String = listOf(
    when (filter.cinemas.size) {
        0 -> "All cinemas"
        1 -> cinemaNames[filter.cinemas.single()]?.let(::isolate) ?: "1 cinema"
        else -> "${filter.cinemas.size} cinemas"
    },
    timeRangeText(filter.from, filter.to),
    (filter.versions.sortedBy { it.ordinal }.map { it.label } + filter.events.sorted().map(::isolate)).joinToString(", ").ifEmpty { "All versions" },
).joinToString(" · ")

/** "16:00–17:30", "from 16:00", "until 17:30", "Any time". */
internal fun timeRangeText(from: LocalTime?, to: LocalTime?): String = when {
    from == null && to == null -> "Any time"
    to == null -> "from ${timeText(from!!)}"
    from == null -> "until ${timeText(to)}"
    else -> "${timeText(from)}–${timeText(to)}"
}

/** "Part of: Autumn Classics" (the specials' titles isolated, spec §11.1). */
internal fun partOfText(titles: List<String>): String = "Part of: " + titles.joinToString(", ") { isolate(it) }

/** "1 showing", "12 showings". */
internal fun showingsText(count: Int): String = if (count == 1) "1 showing" else "$count showings"

/** Spoken summary of a showing for its actions: "The Quiet Projectionist, Thursday 15 Oct, 16:30, delphi LUX". */
internal fun showingSpokenName(item: ShowingItem): String =
    "${isolate(item.filmTitle)}, ${dayText(item.start.toLocalDate())}, ${timeText(item.start)}, ${isolate(item.cinemaName)}"
