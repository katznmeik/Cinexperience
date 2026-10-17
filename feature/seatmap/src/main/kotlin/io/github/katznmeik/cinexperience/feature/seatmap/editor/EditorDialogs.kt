package io.github.katznmeik.cinexperience.feature.seatmap.editor

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.katznmeik.cinexperience.core.ui.component.MutedText

/** Confirmation and error dialogs (no text fields, so they also work in UI tests). */
@Composable
internal fun EditorDialogHost(dialog: EditorDialog?, actions: LayoutEditorActions) {
    when (dialog) {
        null -> Unit
        EditorDialog.ConfirmDiscard -> AlertDialog(
            onDismissRequest = actions::onDismissDialog,
            title = { Text("Discard changes?") },
            text = { MutedText("Your changes to this layout will be lost.") },
            confirmButton = { TextButton(onClick = actions::onConfirmDiscard) { Text("Discard") } },
            dismissButton = { TextButton(onClick = actions::onDismissDialog) { Text("Keep editing") } },
        )
        is EditorDialog.ConfirmSave -> AlertDialog(
            onDismissRequest = actions::onDismissDialog,
            title = { Text(if (dialog.removedSeats.isEmpty()) "Replace the saved layout?" else "Remove seats with visits?") },
            text = { MutedText(confirmSaveText(dialog)) },
            confirmButton = { TextButton(onClick = actions::onConfirmSave) { Text("Save anyway") } },
            dismissButton = { TextButton(onClick = actions::onDismissDialog) { Text("Keep editing") } },
        )
        is EditorDialog.SaveFailed -> AlertDialog(
            onDismissRequest = actions::onDismissDialog,
            title = { Text("Couldn't save") },
            text = { MutedText(dialog.message) },
            confirmButton = {
                if (dialog.canReload) {
                    TextButton(onClick = actions::onReload) { Text("Reload") }
                } else {
                    TextButton(onClick = actions::onDismissDialog) { Text("OK") }
                }
            },
            dismissButton = if (dialog.canReload) {
                { TextButton(onClick = actions::onDismissDialog) { Text("Keep editing") } }
            } else {
                null
            },
        )
    }
}

/** Body of the §6.5 warning: which seats with data would disappear. */
internal fun confirmSaveText(dialog: EditorDialog.ConfirmSave): String = buildString {
    val seats = dialog.removedSeats
    if (seats.isNotEmpty()) {
        val shown = seats.take(MAX_LISTED_SEATS).joinToString("\n") { "• ${it.description}" }
        val more = seats.size - MAX_LISTED_SEATS
        append(if (seats.size == 1) "This seat has visits or seat settings and will be removed:\n" else "These ${seats.size} seats have visits or seat settings and will be removed:\n")
        append(shown)
        if (more > 0) append("\n… and $more more")
        append("\n\nTheir visits are kept, without a seat. Colours and favourites of these seats are deleted.")
    }
    if (dialog.replacesUnreadableLayout) {
        if (isNotEmpty()) append("\n\n")
        append("The saved layout can't be read by this version of the app and will be replaced.")
        when (val count = dialog.unreadableSeatsWithData) {
            0 -> Unit
            1 -> append(" 1 of its seats has visits or seat settings: its visits will keep no seat, and its colour and favourite are deleted.")
            else -> append(
                " $count of its seats have visits or seat settings: their visits will keep no seat, " +
                    "and their colours and favourites are deleted.",
            )
        }
    }
}

private const val MAX_LISTED_SEATS = 12
