package io.github.katznmeik.cinexperience.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp

/**
 * Width of [text] on one line in [style] at the current text size, e.g. to keep a
 * column as wide as its widest value ("100 %", two digits) while the value changes.
 */
@Composable
fun rememberTextWidth(text: String, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(text, style, density) {
        with(density) { measurer.measure(text, style, softWrap = false).size.width.toDp() }
    }
}
