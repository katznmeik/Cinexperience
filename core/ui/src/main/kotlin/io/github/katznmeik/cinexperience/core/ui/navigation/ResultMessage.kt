package io.github.katznmeik.cinexperience.core.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import kotlinx.coroutines.flow.StateFlow

/**
 * Saved-state key of a one-off message that a closing screen leaves for the screen
 * it returns to, e.g. "Deleted Atelier" after a cinema detail deleted its cinema.
 */
const val RESULT_MESSAGE_KEY: String = "cinemas.resultMessage"

/**
 * Goes back and leaves [message] for the screen below, which shows it
 * ([resultMessage]), whichever feature that screen belongs to.
 */
fun NavController.navigateUpWithResultMessage(message: String) {
    previousBackStackEntry?.savedStateHandle?.leaveResultMessage(message)
    navigateUp()
}

/** The message left for this screen by [navigateUpWithResultMessage], and a way to clear it once shown. */
@Composable
fun NavBackStackEntry.resultMessage(): Pair<String?, () -> Unit> {
    val handle = savedStateHandle
    val message by handle.resultMessages().collectAsStateWithLifecycle()
    return message to { handle.clearResultMessage() }
}

internal fun SavedStateHandle.leaveResultMessage(message: String) {
    this[RESULT_MESSAGE_KEY] = message
}

internal fun SavedStateHandle.resultMessages(): StateFlow<String?> = getStateFlow(RESULT_MESSAGE_KEY, null)

internal fun SavedStateHandle.clearResultMessage() {
    this[RESULT_MESSAGE_KEY] = null
}
