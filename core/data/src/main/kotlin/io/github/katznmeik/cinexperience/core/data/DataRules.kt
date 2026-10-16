package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.data.json.LayoutJson
import io.github.katznmeik.cinexperience.core.model.TextLimits

/**
 * Rules for stored values that both the repositories (when saving) and the backup
 * validator (on import, export and safety copies) enforce, so that whatever the
 * app saved can always be exported again. Texts: [TextLimits]; layouts:
 * [LayoutJson.MAX_JSON_LENGTH] and the `SeatLayout` limits.
 */
internal object DataRules {
    /** A hall's screen width in seat widths: finite and positive, or `null` (not set). The editor's minimum is 1. */
    fun isValidScreenWidth(width: Float?): Boolean = width == null || (width.isFinite() && width > 0f)

    /** A hall's screen distance in row depths: finite and not negative, or `null` (not set). */
    fun isValidScreenDistance(distance: Float?): Boolean = distance == null || (distance.isFinite() && distance >= 0f)
}
