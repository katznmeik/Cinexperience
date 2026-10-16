package io.github.katznmeik.cinexperience.feature.cinemas

import io.github.katznmeik.cinexperience.core.model.Cinema
import io.github.katznmeik.cinexperience.core.model.HallSummary
import io.github.katznmeik.cinexperience.core.model.formatScore
import io.github.katznmeik.cinexperience.core.ui.text.countText

/** "no visits", "1 visit", "8 visits". */
internal fun visitsText(count: Int): String = if (count == 0) "no visits" else countText(count, "visit")

/** A list row's score for screen readers: "Score 4.2" or "Not rated", instead of the bare "4.2" or "–". */
internal fun scoreDescription(score: Double?): String = score?.let { "Score ${formatScore(it)}" } ?: "Not rated"

/** Street part of the address ("Kantstraße 10" of "Kantstraße 10, 10623 Berlin"), or `null`. */
internal val Cinema.shortAddress: String?
    get() = address?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }

/** Cinema list subtitle, e.g. "Kantstraße 10 · 7 halls". */
internal fun cinemaMeta(cinema: Cinema, hallCount: Int): String =
    listOfNotNull(cinema.shortAddress, countText(hallCount, "hall")).joinToString(" · ")

/** Hall row subtitle, e.g. "180 seats · 4 visits" or "Layout not drawn yet". */
internal fun hallMeta(hall: HallSummary): String {
    val layout = hall.hall.layout
    val layoutText = when {
        layout != null -> countText(layout.seatCount, "seat")
        hall.hall.layoutUnreadable -> "Layout can't be shown"
        else -> "Layout not drawn yet"
    }
    return if (layout == null && hall.visitCount == 0) layoutText else "$layoutText · ${visitsText(hall.visitCount)}"
}

/**
 * Hall row subtitle in Manage cinemas & halls: "Added by you · " before [hallMeta]
 * only for a hall the user created (not preloaded, no seed key). Seed halls the
 * seeder added to a user-added cinema aren't preloaded but weren't added by the
 * user either; they carry a seed key and get no origin label. An adopted hall
 * (created by the user, then matched to a seed hall) carries one too, so it loses
 * the label: accepted, the subtitle stays neutral rather than wrong. A preloaded
 * hall without a seed key (only from a crafted backup) is never "Added by you"
 * next to its disabled Delete.
 */
internal fun manageHallMeta(hall: HallSummary): String =
    if (!hall.hall.isPreloaded && hall.hall.seedKey == null) "Added by you · ${hallMeta(hall)}" else hallMeta(hall)
