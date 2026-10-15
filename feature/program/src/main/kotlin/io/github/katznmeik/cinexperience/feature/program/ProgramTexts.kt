package io.github.katznmeik.cinexperience.feature.program

import io.github.katznmeik.cinexperience.core.model.formatScore
import io.github.katznmeik.cinexperience.core.program.Film
import io.github.katznmeik.cinexperience.core.program.ProgramTime
import io.github.katznmeik.cinexperience.core.program.ShowingVersion
import io.github.katznmeik.cinexperience.core.ui.text.dateText
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH)
private val SHORT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val AS_OF: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

/**
 * Wraps a text from Yorck's page (title, tagline, genre, label, cinema name) in
 * Unicode first-strong isolates (U+2068 … U+2069), so right-to-left or unusual
 * characters in it can't visually reorder the times, names and numbers next to it
 * when it is joined into one string or content description. Texts shown alone in
 * their own Text don't need it.
 */
internal fun isolate(text: String): String = "\u2068$text\u2069"

/** "16:30" */
internal fun timeText(time: LocalTime): String = TIME.format(time)

internal fun timeText(dateTime: LocalDateTime): String = timeText(dateTime.toLocalTime())

/** Day header: "Today · Thursday 15 Oct", "Tomorrow · Friday 16 Oct", "Saturday 17 Oct". */
internal fun dayHeaderText(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today · ${DAY.format(date)}"
    today.plusDays(1) -> "Tomorrow · ${DAY.format(date)}"
    else -> DAY.format(date)
}

/** "Sat 17 Oct" */
internal fun shortDayText(date: LocalDate): String = SHORT_DAY.format(date)

/** "As of 15 Oct 2026, 10:00" (Berlin time, like the program). */
internal fun asOfText(instant: Instant): String = "As of ${AS_OF.format(instant.atZone(ProgramTime.ZONE))}"

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
        1 -> cinemaNames[filter.cinemas.single()] ?: "1 cinema"
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
    "${isolate(item.filmTitle)}, ${DAY.format(item.start)}, ${timeText(item.start)}, ${isolate(item.cinemaName)}"
