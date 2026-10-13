package io.github.katznmeik.cinexperience.feature.cinemas.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.text.countText
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** Callbacks of the edit dialogs, implemented by the screen's ViewModel. */
internal interface EditDialogActions {
    fun onNameChange(name: String)
    fun onAddressChange(address: String)
    fun onNotesChange(notes: String)
    fun onConfirm()
    fun onDismiss()

    /** Delete button inside the edit form of a user-added cinema. */
    fun onDeleteCinema()
}

/** Shows [dialog] (if any). */
@Composable
internal fun EditDialogHost(dialog: EditDialog?, actions: EditDialogActions) {
    when (dialog) {
        null -> Unit
        is EditDialog.CinemaForm -> CinemaFormDialog(dialog, actions)
        is EditDialog.HallName -> HallNameDialog(dialog, actions)
        is EditDialog.ConfirmDeleteCinema -> ConfirmDialog(
            title = "Delete cinema?",
            text = "“${dialog.name}” will be deleted together with its " +
                "${countText(dialog.hallCount, "hall")} and ${countText(dialog.visitCount, "visit")}. " +
                "This can't be undone.",
            actions = actions,
        )
        is EditDialog.ConfirmDeleteHall -> ConfirmDialog(
            title = "Delete hall?",
            text = if (dialog.visitCount == 0) {
                "“${dialog.name}” has no visits. This can't be undone."
            } else {
                "“${dialog.name}” will be deleted together with its ${countText(dialog.visitCount, "visit")}. " +
                    "This can't be undone."
            },
            actions = actions,
        )
    }
}

@Composable
private fun CinemaFormDialog(dialog: EditDialog.CinemaForm, actions: EditDialogActions) {
    AlertDialog(
        onDismissRequest = actions::onDismiss,
        title = { Text(if (dialog.isNew) "Add cinema" else "Edit cinema") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NameField(dialog.name, dialog.nameError, actions::onNameChange)
                OutlinedTextField(
                    value = dialog.address,
                    onValueChange = actions::onAddressChange,
                    label = { Text("Address (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!dialog.isNew) {
                    OutlinedTextField(
                        value = dialog.notes,
                        onValueChange = actions::onNotesChange,
                        label = { Text("Notes (optional)") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (dialog.delete != null) {
                    TextButton(onClick = actions::onDeleteCinema) { Text("Delete cinema") }
                }
            }
        },
        confirmButton = { TextButton(onClick = actions::onConfirm) { Text(if (dialog.isNew) "Add" else "Save") } },
        dismissButton = { TextButton(onClick = actions::onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HallNameDialog(dialog: EditDialog.HallName, actions: EditDialogActions) {
    AlertDialog(
        onDismissRequest = actions::onDismiss,
        title = { Text(if (dialog.isNew) "Add hall" else "Rename hall") },
        text = { NameField(dialog.name, dialog.nameError, actions::onNameChange) },
        confirmButton = { TextButton(onClick = actions::onConfirm) { Text(if (dialog.isNew) "Add" else "Save") } },
        dismissButton = { TextButton(onClick = actions::onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NameField(value: String, error: String?, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Name") },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, actions: EditDialogActions) {
    AlertDialog(
        onDismissRequest = actions::onDismiss,
        title = { Text(title) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.textMuted) },
        confirmButton = { TextButton(onClick = actions::onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = actions::onDismiss) { Text("Cancel") } },
    )
}
