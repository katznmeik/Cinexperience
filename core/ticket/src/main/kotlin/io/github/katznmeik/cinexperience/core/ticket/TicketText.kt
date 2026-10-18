package io.github.katznmeik.cinexperience.core.ticket

/**
 * Cleans text from the ticket for display as plain text: removes control, format
 * (Unicode `Cf`, e.g. zero-width and bidi controls) and unpaired surrogate
 * characters, turns every run of whitespace (also line and paragraph separators and
 * the tabs Yorck's ticket maps its spaces to) into one space and trims. Nothing is
 * decoded or interpreted: `<b>` stays the three characters `<b>`.
 *
 * The same rules as core:program's `ProgramText` (spec §11.1 rule 7), copied so that
 * this module doesn't depend on the network module.
 */
internal object TicketText {
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

    private fun isSpace(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

    private fun isRemoved(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE -> true
        else -> false
    }
}
