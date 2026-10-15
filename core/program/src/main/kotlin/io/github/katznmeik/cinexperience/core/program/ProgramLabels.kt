package io.github.katznmeik.cinexperience.core.program

import java.util.Locale

/**
 * Yorck's showing labels ("formats" on the page): the language versions DF, OmU,
 * OmeU and OV, and event labels, which are shown as display texts.
 */
internal object ProgramLabels {

    /**
     * Display texts of the known event labels, by Yorck's label in lower case.
     * KF is the children's film label (verified by the films that carry it). SV
     * probably marks special screenings, but that isn't confirmed, so it is shown
     * as "SV".
     */
    private val EVENT_TEXTS: Map<String, String> = mapOf(
        "kf" to "Kids",
        "sv" to "SV",
        "inthemood" to "In the Mood",
        "festival" to "Festival",
        "premiere" to "Premiere",
        "sneak" to "Sneak",
        "mongay" to "Mongay",
        "creepy c" to "Creepy C",
    )

    /** Versions and event labels of one showing; [labels] are the raw strings from the page. */
    data class Classified(val versions: List<ShowingVersion>, val eventLabels: List<String>)

    /**
     * Splits [labels] into versions and event display texts, in their order and
     * without repeats. An unknown label is kept as cleaned text of at most
     * [ProgramRules.MAX_EVENT_LABEL_LENGTH] characters (longer ones are dropped).
     */
    fun classify(labels: List<String>): Classified {
        val versions = LinkedHashSet<ShowingVersion>()
        val events = LinkedHashMap<String, String>() // lower-case key → display text
        for (raw in labels.take(ProgramRules.MAX_LABELS_PER_SHOWING)) {
            val label = ProgramText.cleanOrNull(raw, ProgramRules.MAX_EVENT_LABEL_LENGTH) ?: continue
            val version = ShowingVersion.fromLabel(label)
            if (version != null) {
                versions += version
                continue
            }
            val key = label.lowercase(Locale.ROOT)
            val text = EVENT_TEXTS[key] ?: label
            events.putIfAbsent(text.lowercase(Locale.ROOT), text)
        }
        return Classified(versions.toList(), events.values.toList())
    }
}
