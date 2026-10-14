package io.github.katznmeik.cinexperience.core.ui.text

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val VISIT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** A visit date as shown in lists and the visit form, e.g. "12 Sep 2026" (the app is English only). */
fun dateText(date: LocalDate): String = VISIT_DATE.format(date)
