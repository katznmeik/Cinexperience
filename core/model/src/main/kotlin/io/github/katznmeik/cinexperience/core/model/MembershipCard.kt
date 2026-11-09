package io.github.katznmeik.cinexperience.core.model

/**
 * The owner's Yorck membership card ("Kundenkarte", spec §4.8, v1.5): at most one,
 * added from a screenshot (§6.13, §11.4). Kept on the phone only: not in the
 * database, the backup file or Auto Backup.
 *
 * @property name the name as typed by the owner (the app reads no text from the image).
 * @property memberNumber the "Mitgliedsnummer", as typed.
 * @property membershipType the "Art der Mitgliedschaft", as typed, e.g. "Yorck Unlimited".
 * @property qrModules the card's QR code as read from the screenshot.
 *
 * Each text is `null` (none) or already clean ([MembershipCardText.clean]) and within
 * its limit; [of] cleans typed texts first. [toString] shows no value: the card is
 * personal data and never goes into logs.
 */
data class MembershipCard(
    val name: String?,
    val memberNumber: String?,
    val membershipType: String?,
    val qrModules: QrModules,
) {
    init {
        requireText(name, MAX_NAME_LENGTH, "name")
        requireText(memberNumber, MAX_MEMBER_NUMBER_LENGTH, "membership number")
        requireText(membershipType, MAX_MEMBERSHIP_TYPE_LENGTH, "membership type")
    }

    override fun toString(): String =
        "MembershipCard(name=${hidden(name)}, memberNumber=${hidden(memberNumber)}, " +
            "membershipType=${hidden(membershipType)}, qrModules=$qrModules)"

    companion object {
        /** Longest name (code points). */
        const val MAX_NAME_LENGTH: Int = 100

        /** Longest membership number (code points). */
        const val MAX_MEMBER_NUMBER_LENGTH: Int = 40

        /** Longest membership type (code points). */
        const val MAX_MEMBERSHIP_TYPE_LENGTH: Int = 60

        /** The card with the typed texts cleaned ([MembershipCardText.clean]); throws if one is still too long. */
        fun of(name: String?, memberNumber: String?, membershipType: String?, qrModules: QrModules): MembershipCard =
            MembershipCard(
                MembershipCardText.clean(name),
                MembershipCardText.clean(memberNumber),
                MembershipCardText.clean(membershipType),
                qrModules,
            )

        private fun requireText(text: String?, max: Int, what: String) {
            if (text == null) return
            require(TextLimits.fits(text, max)) { "The $what has more than $max characters" }
            require(MembershipCardText.clean(text) == text) { "The $what isn't clean text" }
        }

        private fun hidden(text: String?): String = if (text == null) "none" else "…"
    }
}

/** The plain-text rule of the membership card's typed texts (spec §4.8). */
object MembershipCardText {
    /**
     * [text] without control and format characters (line breaks included: every text
     * is one line) and without surrounding spaces; `null` if nothing is left.
     */
    fun clean(text: String?): String? {
        if (text == null) return null
        val kept = buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                val type = Character.getType(codePoint)
                val dropped = type == Character.CONTROL.toInt() ||
                    type == Character.FORMAT.toInt() ||
                    type == Character.LINE_SEPARATOR.toInt() ||
                    type == Character.PARAGRAPH_SEPARATOR.toInt() ||
                    type == Character.SURROGATE.toInt() // a lone surrogate
                if (!dropped) appendCodePoint(codePoint)
                i += Character.charCount(codePoint)
            }
        }
        return kept.trim().ifEmpty { null }
    }

    /** Whether [text] is clean and fits [max] code points, as the card's texts must. */
    fun isValid(text: String?, max: Int): Boolean = text == null || (TextLimits.fits(text, max) && clean(text) == text)
}
