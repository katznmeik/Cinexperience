package io.github.katznmeik.cinexperience.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Font scale above which side-by-side layouts (two columns, label beside its
 * control) stack into one column, so words aren't broken.
 */
const val LARGE_TEXT_FONT_SCALE: Float = 1.3f

/** Whether text is scaled above [LARGE_TEXT_FONT_SCALE]. */
val Density.isLargeText: Boolean
    get() = fontScale > LARGE_TEXT_FONT_SCALE

/** Whether the current text size calls for the one-column layout ([LARGE_TEXT_FONT_SCALE]). */
@Composable
@ReadOnlyComposable
fun isLargeText(): Boolean = LocalDensity.current.isLargeText
