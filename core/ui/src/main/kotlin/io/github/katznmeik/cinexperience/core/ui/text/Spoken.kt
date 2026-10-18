package io.github.katznmeik.cinexperience.core.ui.text

/**
 * [text] with a space between its characters, for screen readers: a code such as
 * a booking number ("TEST123" → "T E S T 1 2 3") is read letter by letter and
 * digit by digit, not as a word or a number. A character outside the BMP (two
 * chars) stays whole.
 */
fun spelledOut(text: String): String =
    text.codePoints().toArray().joinToString(" ") { String(Character.toChars(it)) }
