package io.github.katznmeik.cinexperience.core.ui.text

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The app's date and time texts (the app is English only). Times are 24 h. The
// caller picks the time zone: the program's texts use Berlin time (core:program
// ProgramTime), the backup's the device's.
private val VISIT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH)
private val SHORT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** A visit date as shown in lists and the visit form, e.g. "12 Sep 2026". */
fun dateText(date: LocalDate): String = VISIT_DATE.format(date)

/** "3 Oct 2026, 14:05": [instant] in [zone]. */
fun dateTimeText(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DATE_TIME.format(instant.atZone(zone))

/** "16:30" */
fun timeText(time: LocalTime): String = TIME.format(time)

/** "16:30": the time of [dateTime]. */
fun timeText(dateTime: LocalDateTime): String = timeText(dateTime.toLocalTime())

/** "Thursday 15 Oct" */
fun dayText(date: LocalDate): String = DAY.format(date)

/** "Sat 17 Oct" */
fun shortDayText(date: LocalDate): String = SHORT_DAY.format(date)

/** "15 Oct" */
fun dayMonthText(date: LocalDate): String = DAY_MONTH.format(date)

/** Day header: "Today · Thursday 15 Oct", "Tomorrow · Friday 16 Oct", "Saturday 17 Oct". */
fun dayHeaderText(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today · ${dayText(date)}"
    today.plusDays(1) -> "Tomorrow · ${dayText(date)}"
    else -> dayText(date)
}
