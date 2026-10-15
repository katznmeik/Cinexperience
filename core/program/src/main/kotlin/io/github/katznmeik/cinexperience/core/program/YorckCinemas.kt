package io.github.katznmeik.cinexperience.core.program

import java.util.Locale

/**
 * The fixed table from Yorck's cinema names on yorck.de to the app's preloaded
 * cinemas (their `seedKey` in `core/data/src/main/assets/seed/yorck.json`,
 * spec §9, §11.1). Names are compared trimmed, with runs of spaces as one space
 * and ignoring case, so "Blauer Stern " (with Yorck's trailing space) matches.
 * A cinema missing here is shown with Yorck's name and no score.
 */
object YorckCinemas {

    /** Yorck's name (normalised, see [normalise]) → seed key. */
    private val SEED_KEYS: Map<String, String> = mapOf(
        "babylon kreuzberg" to "babylon-kreuzberg",
        "blauer stern" to "blauer-stern",
        "capitol dahlem" to "capitol-dahlem",
        "cinema paris" to "cinema-paris",
        "delphi filmpalast" to "delphi-filmpalast",
        "delphi lux" to "delphi-lux",
        "filmtheater am friedrichshain" to "filmtheater-am-friedrichshain",
        "kant kino" to "kant-kino",
        // The app calls it "International" (spec §9).
        "kino international" to "international",
        "neues off" to "neues-off",
        "odeon" to "odeon",
        "passage" to "passage",
        "rollberg" to "rollberg",
        "yorck" to "yorck",
    )

    /** Every seed key in the table. */
    val seedKeys: Set<String> get() = SEED_KEYS.values.toSet()

    /** The seed key of the app cinema Yorck calls [yorckName], `null` if there is none. */
    fun seedKeyFor(yorckName: String): String? = SEED_KEYS[normalise(yorckName)]

    private fun normalise(name: String): String =
        name.trim().split(WHITESPACE).joinToString(" ").lowercase(Locale.ROOT)

    private val WHITESPACE = Regex("\\s+")
}
