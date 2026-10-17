package io.github.katznmeik.cinexperience.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The state of a screen's snackbar for one-off messages: shows [message] (from the
 * screen's state) when it is set, and once the snackbar is gone calls [onShown],
 * which clears it. Place it with [MessageSnackbarHost].
 */
@Composable
fun rememberMessageSnackbarState(message: String?, onShown: () -> Unit): SnackbarHostState {
    val hostState = remember { SnackbarHostState() }
    MessageSnackbarEffect(hostState, message, onShown)
    return hostState
}

/**
 * Shows [message] in [hostState]'s snackbar when it is set, then calls [onShown];
 * for a second source of messages on the same snackbar.
 */
@Composable
fun MessageSnackbarEffect(hostState: SnackbarHostState, message: String?, onShown: () -> Unit) {
    LaunchedEffect(message) {
        message?.let {
            hostState.showSnackbar(it)
            onShown()
        }
    }
}

/** The screen's snackbar, at the bottom centre of the screen's full-size [Box]. */
@Composable
fun BoxScope.MessageSnackbarHost(hostState: SnackbarHostState) {
    SnackbarHost(hostState, Modifier.align(Alignment.BottomCenter))
}
