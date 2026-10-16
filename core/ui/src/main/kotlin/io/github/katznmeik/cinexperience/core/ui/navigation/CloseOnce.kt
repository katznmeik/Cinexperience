package io.github.katznmeik.cinexperience.core.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Calls [onClose] when [isClosed] becomes true (e.g. a form that has saved), exactly
 * once, also if the screen is recreated while it leaves.
 */
@Composable
fun CloseOnce(isClosed: Boolean, onClose: () -> Unit) {
    var closeHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isClosed) {
        if (isClosed && !closeHandled) {
            closeHandled = true
            onClose()
        }
    }
}
