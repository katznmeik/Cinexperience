package io.github.katznmeik.cinexperience.core.program

import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import java.time.LocalDate

/**
 * The visit's screening format for this version (spec §11.1, Log visit from a
 * showing): DF → German dub, OmU → OmU, OV → OV; OmeU has no matching format.
 */
fun ShowingVersion.toScreeningFormat(): ScreeningFormat? = when (this) {
    ShowingVersion.DF -> ScreeningFormat.GERMAN_DUB
    ShowingVersion.OMU -> ScreeningFormat.OMU
    ShowingVersion.OV -> ScreeningFormat.OV
    ShowingVersion.OMEU -> null
}

/**
 * The screening format to pre-fill: the format of the showing's versions if they
 * name exactly one (OmeU names none), else `null` (no version, or e.g. DF and OV).
 */
fun Showing.screeningFormat(): ScreeningFormat? =
    versions.mapNotNull { it.toScreeningFormat() }.distinct().singleOrNull()

/**
 * What "Log visit" fills into Add visit (spec §6.4, §11.1). The UI finds the app
 * cinema by [cinemaSeedKey] and pre-selects its hall if it has only one.
 */
data class VisitPrefill(
    val cinemaSeedKey: String?,
    val date: LocalDate,
    val filmTitle: String?,
    val screeningFormat: ScreeningFormat?,
)

/** The pre-fill for a visit to this showing of [film]. */
fun Showing.visitPrefill(film: Film?): VisitPrefill = VisitPrefill(
    cinemaSeedKey = cinema.seedKey,
    date = start.toLocalDate(),
    filmTitle = film?.title,
    screeningFormat = screeningFormat(),
)
