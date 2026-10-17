package io.github.katznmeik.cinexperience.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** Muted body text, e.g. a dialog's text ("Your changes will be lost."). */
@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = CinexTheme.colors.textMuted,
        modifier = modifier,
    )
}

/**
 * A list's empty or error state, e.g. "No cinemas yet. …", 8 dp above and below.
 * [horizontalPadding] comes on top of the list's own side padding.
 */
@Composable
fun InfoText(text: String, horizontalPadding: Dp = 4.dp) {
    MutedText(text, Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp))
}

/** A message in place of a form's content, under its top bar, e.g. "This visit no longer exists." */
@Composable
fun FormMessage(text: String) {
    MutedText(text, Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
}

/**
 * A pushed screen that has only a message to show, e.g. "This hall no longer exists.",
 * with the back button above it.
 */
@Composable
fun BackMessageContent(text: String, onBack: () -> Unit) {
    Column {
        BackTopBar(onBack)
        MutedText(text, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}
