package io.github.katznmeik.cinexperience.core.testing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import kotlin.math.roundToInt

/** The large font scales every main screen is screenshotted at (Android's biggest steps). */
val LARGE_FONT_SCALES: List<Float> = listOf(1.5f, 2f)

/**
 * The font scales the accessibility tests run at: the default size (checked,
 * already screenshotted elsewhere) and the [LARGE_FONT_SCALES] (checked and screenshotted).
 */
val ACCESSIBILITY_FONT_SCALES: List<Float> = listOf(1f) + LARGE_FONT_SCALES

/** "font_150" for 1.5: the file-name suffix of a font-scale screenshot. */
fun fontScaleSuffix(fontScale: Float): String = "font_${(fontScale * 100).roundToInt()}"

/**
 * A screen under test: the app theme on the background colour, optionally with
 * text scaled by [fontScale] (linear scaling, so a bit harsher than Android's
 * non-linear large-font curve: the worst case).
 */
@Composable
fun ScreenshotSurface(fontScale: Float? = null, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
        CinexTheme {
            Box(Modifier.fillMaxSize().background(CinexTheme.colors.background)) { content() }
        }
    }
}

/** Minimum touch target (Material and Android accessibility guidance). */
val MIN_TOUCH_TARGET: Dp = 48.dp

/**
 * Basic TalkBack checks over everything currently composed:
 * - every clickable element has something to announce (text or content description),
 * - every clickable element has a touch target of at least [MIN_TOUCH_TARGET],
 * - no element announces the same text twice (e.g. an icon described with its button's label),
 * - the screen has at least one heading to navigate by (unless [requireHeading] is false).
 *
 * Fails with a list of all problems found.
 */
fun SemanticsNodeInteractionsProvider.assertAccessible(requireHeading: Boolean = true) {
    val problems = mutableListOf<String>()
    val clickable = onAllNodes(hasClickAction()).fetchSemanticsNodes(atLeastOneRootRequired = true)
    // Skips elements clipped away completely (e.g. lazy list items composed beyond the viewport).
    clickable.filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }.forEach { node ->
        val spoken = node.spokenTexts()
        val name = spoken.joinToString(" / ").ifEmpty { "<unlabelled node ${node.id}>" }
        if (spoken.none { it.isNotBlank() }) problems += "Clickable element without a label: $name at ${node.boundsInRoot}"
        if (!node.hasMinimumTouchTarget()) {
            val density = node.layoutInfo.density
            val width = with(density) { node.size.width.toDp() }
            val height = with(density) { node.size.height.toDp() }
            problems += "Touch target smaller than $MIN_TOUCH_TARGET: \"$name\" is $width × $height"
        }
    }
    onAllNodes(hasAnyLabel()).fetchSemanticsNodes(atLeastOneRootRequired = true).forEach { node ->
        val spoken = node.spokenTexts().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        // Words only: the same score or "–" in two columns is legitimate.
        val repeated = spoken.filter { text -> text.count { it.isLetter() } > 2 }.groupBy { it }.filterValues { it.size > 1 }.keys
        if (repeated.isNotEmpty()) problems += "Announced twice: ${repeated.joinToString()} in \"${spoken.joinToString(" / ")}\""
    }
    if (requireHeading && onAllNodes(isHeading()).fetchSemanticsNodes(atLeastOneRootRequired = true).isEmpty()) {
        problems += "No heading on the screen"
    }
    if (problems.isNotEmpty()) throw AssertionError("Accessibility problems:\n" + problems.distinct().joinToString("\n") { " - $it" })
}

/**
 * Whether the clickable element itself (the bounds of its semantics, not padding
 * around it) is at least [MIN_TOUCH_TARGET], or Material's
 * `minimumInteractiveComponentSize()` reserves 48 dp around it. Compose's
 * `touchBoundsInRoot` can't tell: it extends every small pointer-input node to 48 dp.
 */
internal fun SemanticsNode.hasMinimumTouchTarget(): Boolean {
    val layout = layoutInfo
    val minPx = with(layout.density) { MIN_TOUCH_TARGET.toPx() } - 0.5f
    if (size.width >= minPx && size.height >= minPx) return true
    val enlargedByMaterial = layout.getModifierInfo().any { it.modifier::class.qualifiedName == MINIMUM_INTERACTIVE_MODIFIER }
    return enlargedByMaterial && layout.width >= minPx && layout.height >= minPx
}

/** Element class of `Modifier.minimumInteractiveComponentSize()` (Material 3). */
private const val MINIMUM_INTERACTIVE_MODIFIER = "androidx.compose.material3.MinimumInteractiveModifier"

private fun hasAnyLabel() = androidx.compose.ui.test.SemanticsMatcher("has text or content description") { node ->
    node.spokenTexts().isNotEmpty()
}

/** What TalkBack reads for a (merged) node: content descriptions, then texts. */
private fun SemanticsNode.spokenTexts(): List<String> {
    val config = config
    val descriptions = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    val texts = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    val editable = config.getOrNull(SemanticsProperties.EditableText)?.text?.let(::listOf).orEmpty()
    return descriptions + texts + editable
}
