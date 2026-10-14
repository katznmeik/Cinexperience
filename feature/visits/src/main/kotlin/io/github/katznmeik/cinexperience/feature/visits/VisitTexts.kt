package io.github.katznmeik.cinexperience.feature.visits

import io.github.katznmeik.cinexperience.core.model.HallCategory
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat

/** Chip text of a screening format in the visit form (mockup: "German" for the German dub). */
internal val ScreeningFormat.chipText: String
    get() = when (this) {
        ScreeningFormat.GERMAN_DUB -> "German"
        else -> displayName
    }

/** Row label of a hall category in the visit form; "Steepness" is short for "Seating steepness". */
internal val HallCategory.formLabel: String
    get() = when (this) {
        HallCategory.STEEPNESS -> "Steepness"
        else -> displayName
    }

/** Title shown for a visit without film title. */
internal const val UNTITLED = "Untitled"
