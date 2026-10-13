package io.github.katznmeik.cinexperience.core.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Like `androidx.lifecycle.compose.dropUnlessResumed`, for navigation callbacks that
 * take an argument (e.g. an id): calls are dropped unless the screen is resumed, so
 * double taps during a transition don't navigate twice.
 */
@Composable
fun <T> dropUnlessResumedWith(block: (T) -> Unit): (T) -> Unit {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentBlock by rememberUpdatedState(block)
    return remember(lifecycleOwner) {
        { value: T ->
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) currentBlock(value)
        }
    }
}
