package io.github.katznmeik.cinexperience.core.model

import java.math.BigInteger
import java.text.Collator
import java.util.Locale

/**
 * Natural, case-insensitive name order: "delphi LUX" sorts among the D's and
 * "Saal 2" before "Saal 10". The order of every name-sorted repository list.
 */
object NameOrder : Comparator<String> {
    private val collator: Collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.SECONDARY }
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val left = chunk.findAll(a).map { it.value }.toList()
        val right = chunk.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val result = if (x[0] in '0'..'9' && y[0] in '0'..'9') {
                BigInteger(x).compareTo(BigInteger(y))
            } else {
                collator.compare(x, y)
            }
            if (result != 0) return result
        }
        return (left.size - right.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
