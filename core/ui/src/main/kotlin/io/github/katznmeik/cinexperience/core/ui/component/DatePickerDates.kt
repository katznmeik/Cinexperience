package io.github.katznmeik.cinexperience.core.ui.component

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// Material's date picker works in UTC midnight millis; the app's dates are plain LocalDates.

/** The date picker's millis for this date (UTC midnight). */
fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** The date of a date picker's millis (UTC). */
fun Long.toPickerDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
