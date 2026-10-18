package io.github.katznmeik.cinexperience.feature.visits.edit

import java.time.LocalDate

/**
 * The dates a visit can have: the years the form's date picker offers (Material's
 * default range, set explicitly on the picker). A pre-filled date (from a showing or
 * an imported ticket) or a restored form date outside them is ignored, so no date the
 * form can't show reaches the start-time and QR-code arithmetic.
 */
internal object VisitDates {
    val YEARS: IntRange = 1900..2100

    fun isAllowed(date: LocalDate): Boolean = date.year in YEARS
}
