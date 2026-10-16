package io.github.katznmeik.cinexperience.feature.program

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.component.RowDivider
import io.github.katznmeik.cinexperience.core.ui.component.RowGroup
import io.github.katznmeik.cinexperience.core.ui.component.TopBarIconButton
import io.github.katznmeik.cinexperience.core.ui.component.toPickerDate
import io.github.katznmeik.cinexperience.core.ui.component.toPickerMillis
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.text.isolate
import io.github.katznmeik.cinexperience.core.ui.text.takeCodePoints
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import java.time.LocalDate
import java.time.LocalTime

/** The saved filters: pick one, delete one, or save the current filter. */
@Composable
internal fun SavedFiltersDialog(saved: List<SavedFilter>, actions: ProgramActions) {
    AlertDialog(
        onDismissRequest = actions::onDismissDialog,
        title = { Text("Saved filters") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (saved.isEmpty()) {
                    Text(
                        "No saved filters yet. Save the current filter to pick it again later.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinexTheme.colors.textMuted,
                    )
                } else {
                    RowGroup {
                        saved.forEachIndexed { index, entry ->
                            if (index > 0) RowDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    entry.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = CinexTheme.colors.text,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .clickable(role = Role.Button, onClickLabel = "Apply filter") { actions.onApplySavedFilter(entry.name) }
                                        .padding(horizontal = 14.dp, vertical = 14.dp),
                                )
                                TopBarIconButton(CinexIcons.Delete, "Delete ${isolate(entry.name)}", { actions.onDeleteSavedFilter(entry.name) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { actions.onOpenDialog(ProgramDialog.SAVE_FILTER) }) { Text("Save current filter") } },
        dismissButton = { TextButton(onClick = actions::onDismissDialog) { Text("Close") } },
    )
}

/** Asks for a name and saves the current filter under it. */
@Composable
internal fun SaveFilterDialog(actions: ProgramActions) {
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = actions::onDismissDialog,
        title = { Text("Save filter") },
        text = {
            SaveFilterName(name, error, onNameChange = {
                name = it
                error = null
            })
        },
        confirmButton = { TextButton(onClick = { error = actions.onSaveFilter(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = actions::onDismissDialog) { Text("Cancel") } },
    )
}

/** The name field of "Save filter" (at most 40 code points; an emoji is never cut in half). */
@Composable
internal fun SaveFilterName(name: String, error: String?, onNameChange: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = { onNameChange(it.takeCodePoints(ProgramFilterCodec.MAX_NAME_LENGTH)) },
        label = { Text("Name") },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Saving under a name that is already used asks first. */
@Composable
internal fun ReplaceFilterDialog(name: String, actions: ProgramActions) {
    AlertDialog(
        onDismissRequest = actions::onDismissDialog,
        title = { Text("Replace “${isolate(name)}”?") },
        text = {
            Text(
                "A saved filter with this name exists. It gets the current filter.",
                style = MaterialTheme.typography.bodyMedium,
                color = CinexTheme.colors.textMuted,
            )
        },
        confirmButton = { TextButton(onClick = actions::onConfirmReplace) { Text("Replace") } },
        dismissButton = { TextButton(onClick = actions::onDismissDialog) { Text("Cancel") } },
    )
}

/** Earliest or latest start time (24 h); "Any" clears it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialog(title: String, current: LocalTime?, onTime: (LocalTime?) -> Unit, onDismiss: () -> Unit) {
    val initial = current ?: LocalTime.of(18, 0)
    val pickerState = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimeInput(state = pickerState) },
        confirmButton = { TextButton(onClick = { onTime(LocalTime.of(pickerState.hour, pickerState.minute)) }) { Text("Set") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onTime(null) }) { Text("Any") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Adds a date between [today] and the program's last day ([lastDate]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateDialog(today: LocalDate, lastDate: LocalDate?, onDate: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val last = lastDate?.takeIf { !it.isBefore(today) } ?: today.plusYears(1)
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = today.toPickerMillis(),
        yearRange = today.year..last.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = utcTimeMillis.toPickerDate()
                return !date.isBefore(today) && !date.isAfter(last)
            }

            override fun isSelectableYear(year: Int): Boolean = year in today.year..last.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { pickerState.selectedDateMillis?.let { onDate(it.toPickerDate()) } ?: onDismiss() }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState, showModeToggle = false, modifier = Modifier.padding(bottom = 4.dp))
    }
}
