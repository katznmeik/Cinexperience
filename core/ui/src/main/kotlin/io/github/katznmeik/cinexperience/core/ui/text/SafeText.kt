package io.github.katznmeik.cinexperience.core.ui.text

/**
 * The first [max] Unicode code points of this text. Unlike `take`, it never
 * splits a surrogate pair (an emoji or another character outside the BMP counts once).
 */
fun String.takeCodePoints(max: Int): String =
    if (codePointCount(0, length) <= max) this else substring(0, offsetByCodePoints(0, max))

/** The length of this text in Unicode code points. */
val String.codePointLength: Int get() = codePointCount(0, length)

/**
 * Wraps [text] in Unicode first-strong isolates (U+2068 … U+2069), so
 * right-to-left or unusual characters in it (e.g. a film title from Yorck's page)
 * can't visually reorder the times, names and numbers next to it when it is
 * joined into one string or content description. A text shown alone in its own
 * Text doesn't need it.
 */
fun isolate(text: String): String = "\u2068$text\u2069"
