package io.github.katznmeik.cinexperience.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute

/**
 * The one set of screen transitions of the app (spec §7), used by the NavHost in
 * [CinexApp]:
 * - **Opening a screen** (cinema, hall, visit, editor, …) pushes it in from the
 *   right while the current screen moves out to the left, both by the full width
 *   and in step, so the two never overlap (no crossfade of two screens' content).
 * - **Back** (button or gesture) is the exact reverse. The predictive back gesture
 *   seeks the same animation with the finger and, when released, finishes it from
 *   there (or returns, if cancelled): the screen leaves by sliding out, never by
 *   being cut off.
 * - **Switching tabs** is a quick fade-through: the old tab fades out, then the new
 *   one fades in, so their contents don't mix.
 *
 * Durations follow Material motion: [DURATION_MS] for pushes and pops, shorter
 * for tab fades.
 */
object CinexTransitions {
    /** Push / pop duration. */
    const val DURATION_MS = 300

    /** Tab fade-through: out, then in. */
    const val TAB_FADE_OUT_MS = 90
    const val TAB_FADE_IN_MS = 210

    private fun <T> slideSpec() = tween<T>(DURATION_MS, easing = FastOutSlowInEasing)

    private val tabEnter: EnterTransition =
        fadeIn(tween(TAB_FADE_IN_MS, delayMillis = TAB_FADE_OUT_MS, easing = LinearOutSlowInEasing))
    private val tabExit: ExitTransition = fadeOut(tween(TAB_FADE_OUT_MS, easing = FastOutSlowInEasing))

    private val NavBackStackEntry.isTab: Boolean
        get() = TopLevelDestination.entries.any { destination.hasRoute(it.route::class) }

    /**
     * A forward navigation is a tab switch when the bottom bar started it (it may
     * land on a restored detail of that tab) or when it lands on a tab's start
     * screen; otherwise it opens a screen.
     */
    private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(tabSwitch: TabSwitchTracker): Boolean =
        targetState.isTab || targetState.id == tabSwitch.entryId

    /** Back from one tab's start screen to the start tab is a tab switch too. */
    private val AnimatedContentTransitionScope<NavBackStackEntry>.isTabPop: Boolean
        get() = initialState.isTab && targetState.isTab

    fun enter(tabSwitch: TabSwitchTracker): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (isTabSwitch(tabSwitch)) tabEnter else slideInHorizontally(slideSpec<IntOffset>()) { width -> width }
    }

    fun exit(tabSwitch: TabSwitchTracker): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (isTabSwitch(tabSwitch)) tabExit else slideOutHorizontally(slideSpec<IntOffset>()) { width -> -width }
    }

    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (isTabPop) tabEnter else slideInHorizontally(slideSpec<IntOffset>()) { width -> -width }
    }

    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (isTabPop) tabExit else slideOutHorizontally(slideSpec<IntOffset>()) { width -> width }
    }

    /**
     * Predictive back uses the same pop slide (the gesture seeks it), instead of
     * Navigation's default, which shrinks the leaving screen to 70 % without fading
     * it and then removes it in one frame.
     */
    val predictivePopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.(swipeEdge: Int) -> EnterTransition = {
        if (isTabPop) tabEnter else slideInHorizontally(gestureSpec<IntOffset>()) { width -> -width }
    }

    val predictivePopExit: AnimatedContentTransitionScope<NavBackStackEntry>.(swipeEdge: Int) -> ExitTransition = {
        if (isTabPop) tabExit else slideOutHorizontally(gestureSpec<IntOffset>()) { width -> width }
    }

    /**
     * Linear, so the seeked slide moves exactly with the finger (half the swipe =
     * half the width) and the rest after release continues at the same pace.
     */
    private fun <T> gestureSpec() = tween<T>(DURATION_MS, easing = LinearEasing)
}

/**
 * Remembers which back stack entry the last bottom-bar tap landed on, so its
 * transition is a tab fade even when the restored tab shows a detail on top.
 */
class TabSwitchTracker {
    /** [NavBackStackEntry.id] of the entry the last tab switch landed on. */
    var entryId: String? = null
}
