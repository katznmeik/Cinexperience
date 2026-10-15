package io.github.katznmeik.cinexperience.core.program

/**
 * The checks every value from Yorck's page has to pass (spec §11.1, Network &
 * security rule 7). The page is untrusted data: the parser, the cache reader and
 * the model types' `init` blocks all use these rules, so a [Film] or [Showing]
 * that breaks them can't exist.
 */
internal object ProgramRules {
    /** Upper limits, far above today's numbers (114 films, about 1,070 showings). */
    const val MAX_FILMS: Int = 2_000
    const val MAX_SHOWINGS: Int = 30_000

    const val MAX_TITLE_LENGTH: Int = 200
    const val MAX_TAGLINE_LENGTH: Int = 500
    const val MAX_GENRE_LENGTH: Int = 60
    const val MAX_CINEMA_NAME_LENGTH: Int = 80
    const val MAX_EVENT_LABEL_LENGTH: Int = 24
    const val MAX_SLUG_LENGTH: Int = 120

    /** At most this many labels of one showing are looked at; the rest is ignored. */
    const val MAX_LABELS_PER_SHOWING: Int = 16

    val RUNTIME_MINUTES: IntRange = 1..600
    val FSK_RATINGS: Set<Int> = setOf(0, 6, 12, 16, 18)

    // Only ASCII digits and letters; no anchors needed, Regex.matches() covers the whole input.
    private val SHOWING_ID = Regex("[0-9]{1,6}-[0-9]{1,12}")
    private val SLUG = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

    /** A showing ID such as `1001-89886`: digits, a dash, digits. */
    fun isValidShowingId(id: String): Boolean = id.length <= 19 && SHOWING_ID.matches(id)

    /** A page name such as `the-quiet-hour`: lowercase ASCII letters and digits in dash-separated groups. */
    fun isValidSlug(slug: String): Boolean = slug.length <= MAX_SLUG_LENGTH && SLUG.matches(slug)

    /** True if [text] is already [ProgramText.clean] and has 1 to [maxLength] characters. */
    fun isValidText(text: String, maxLength: Int): Boolean =
        ProgramText.length(text) in 1..maxLength && ProgramText.clean(text) == text
}

/**
 * Cleans text from the page for display as plain text: removes control, format
 * (Unicode `Cf`, e.g. zero-width and bidi controls), unpaired surrogate and
 * line/paragraph separator characters, turns every run of whitespace into one
 * space and trims. Same character classes as `SeatLayout.Seat.isValidFixedLabel`.
 * Nothing is decoded or interpreted: `<b>` stays the three characters `<b>`.
 */
internal object ProgramText {
    fun clean(raw: String): String {
        val out = StringBuilder(raw.length)
        var pendingSpace = false
        var i = 0
        while (i < raw.length) {
            val codePoint = raw.codePointAt(i)
            i += Character.charCount(codePoint)
            when {
                isSpace(codePoint) -> pendingSpace = out.isNotEmpty()
                isRemoved(codePoint) -> Unit
                else -> {
                    if (pendingSpace) out.append(' ')
                    pendingSpace = false
                    out.appendCodePoint(codePoint)
                }
            }
        }
        return out.toString()
    }

    /** Length in Unicode code points (a character outside the BMP counts once). */
    fun length(text: String): Int = text.codePointCount(0, text.length)

    /** [clean]s [raw] and returns it if it has 1 to [maxLength] characters, else `null`. */
    fun cleanOrNull(raw: String?, maxLength: Int): String? =
        raw?.let(::clean)?.takeIf { length(it) in 1..maxLength }

    private fun isSpace(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

    private fun isRemoved(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE -> true
        else -> false
    }
}
