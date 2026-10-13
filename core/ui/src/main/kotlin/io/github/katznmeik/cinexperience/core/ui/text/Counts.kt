package io.github.katznmeik.cinexperience.core.ui.text

/** "1 hall", "3 halls", "0 halls". */
fun countText(count: Int, singular: String, plural: String = singular + "s"): String =
    "$count ${if (count == 1) singular else plural}"
