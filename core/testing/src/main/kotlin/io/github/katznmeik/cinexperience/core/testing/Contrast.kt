package io.github.katznmeik.cinexperience.core.testing

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/** WCAG 2.x contrast ratio of two opaque colours (1 to 21). Translucent colours: use [compositeOver] first. */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/** Minimum contrast for normal text (WCAG 1.4.3 AA). */
const val TEXT_CONTRAST: Double = 4.5

/** Minimum contrast for large text and meaningful graphics or control boundaries (WCAG 1.4.3 / 1.4.11 AA). */
const val NON_TEXT_CONTRAST: Double = 3.0
