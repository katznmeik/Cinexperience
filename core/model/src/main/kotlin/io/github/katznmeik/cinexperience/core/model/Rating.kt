package io.github.katznmeik.cinexperience.core.model

/**
 * A single 1–5 rating as entered by the user (spec §4.4). Ratings are always
 * optional, so "not rated" is modelled as a `null` [Rating], never as 0.
 */
@JvmInline
value class Rating(val value: Int) {
    init {
        require(value in RANGE) { "Rating must be in $RANGE, was $value" }
    }

    override fun toString(): String = value.toString()

    companion object {
        val RANGE: IntRange = 1..5

        /** Returns a [Rating] for values in 1–5 and `null` for anything else (e.g. 0 = cleared). */
        fun ofOrNull(value: Int?): Rating? = value?.takeIf { it in RANGE }?.let(::Rating)
    }
}
