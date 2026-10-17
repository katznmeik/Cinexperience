package io.github.katznmeik.cinexperience.core.model

import java.time.LocalDate

/**
 * What a new visit is pre-filled with ("Log visit" from a showing, spec §11.1):
 * feature:program builds it, feature:visits opens Add visit with it. The form
 * selects the cinema's hall if it has only one; otherwise the cinema is set and
 * the owner picks the hall. `null` fields stay as for a blank visit.
 */
data class VisitPrefillArgs(
    val cinemaId: Long? = null,
    val date: LocalDate? = null,
    val filmTitle: String? = null,
    val format: ScreeningFormat? = null,
)
