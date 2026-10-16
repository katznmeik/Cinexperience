package io.github.katznmeik.cinexperience.core.model

/**
 * Upper bounds for the texts the app stores, in Unicode code points (an emoji
 * counts once). The repositories refuse longer texts and the backup validator
 * refuses backups with them (spec §8 "size limits"), so a backup can't bring in a
 * text that makes its database row unreadable (Android reads at most 2 MB per
 * row) or every list slow to draw, and data the app saved always exports.
 *
 * The limits are far above anything real: the visit form allows 200 code points
 * for a film title and 4,000 for a note, the longest seed text has 37. At 4 bytes
 * per code point a whole row stays far below 2 MB. The forms cap their fields
 * at these limits or lower, so the repositories' check is only a defence.
 */
object TextLimits {
    /** Names of cinemas and halls, a cinema's address, seed keys and other keys. */
    const val MAX_NAME_LENGTH: Int = 1_000

    /** Notes of cinemas, halls and visits, and a visit's film title. */
    const val MAX_LONG_TEXT_LENGTH: Int = 20_000

    /**
     * A layout row's label ("7", "C", "R1"): the editor allows 6 characters, the
     * longest seed label has 2. Longer labels would widen the map's label column
     * until the seats shrink to nothing. Checked by the repositories and the backup
     * validator, not by `LayoutRow` itself.
     */
    const val MAX_ROW_LABEL_LENGTH: Int = 16

    /** Whether [text] (`null` = no text) has at most [max] code points. */
    fun fits(text: String?, max: Int): Boolean = when {
        text == null || text.length <= max -> true
        // A code point is one or two chars: more than twice [max] chars are too many.
        text.length > 2L * max -> false
        else -> text.codePointCount(0, text.length) <= max
    }
}
