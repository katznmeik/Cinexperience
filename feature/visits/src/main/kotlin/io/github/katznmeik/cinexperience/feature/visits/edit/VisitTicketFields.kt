package io.github.katznmeik.cinexperience.feature.visits.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.katznmeik.cinexperience.core.model.BookingNumber
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.Ticket
import io.github.katznmeik.cinexperience.core.model.TicketDraft
import io.github.katznmeik.cinexperience.core.model.TicketSeat
import io.github.katznmeik.cinexperience.core.ui.component.OutlinedPillButton
import io.github.katznmeik.cinexperience.core.ui.component.SectionHeader
import io.github.katznmeik.cinexperience.core.ui.component.isLargeText
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.text.spelledOut
import io.github.katznmeik.cinexperience.core.ui.text.timeText
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import java.time.LocalTime

/** The time the picker starts at for a visit without a start time: a typical evening showing. */
internal val DEFAULT_PICKER_TIME: LocalTime = LocalTime.of(20, 0)

/** The confirmation of "Remove ticket" (spec §6.4). */
internal const val REMOVE_TICKET_TEXT =
    "The booking number, the booking's seats and the QR code are removed from this visit now. The visit stays. " +
        "To show the QR code again, import the ticket again."

/** What screen readers announce for the Start time field. */
internal fun startTimeDescription(time: LocalTime?): String =
    if (time == null) "Start time, not set" else "Start time, ${timeText(time)}"

/**
 * The optional start time (spec §6.4): "20:15", or a muted "Optional"; a tap opens
 * the time picker, "×" clears it.
 */
@Composable
internal fun StartTimeField(time: LocalTime?, onOpen: () -> Unit, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CinexTheme.colors.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                // The description replaces the visible text ("Start time, not set" instead of "Optional").
                .clearAndSetSemantics {
                    contentDescription = startTimeDescription(time)
                    role = Role.Button
                    onClick {
                        onOpen()
                        true
                    }
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FieldText(time?.let(::timeText) ?: "Optional", Modifier.weight(1f), muted = time == null)
        }
        if (time != null) {
            IconButton(onClick = onClear) {
                Icon(CinexIcons.Close, contentDescription = "Clear start time", tint = CinexTheme.colors.textMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Material time picker in a dialog, 24 h, like the date picker: OK takes the time,
 * "Clear" (with a time set) removes it, Cancel keeps what the form has. With large
 * text the dial gives way to the keyboard input (its hour rings would overlap) and
 * "Clear" is left out (the field's "×" clears); the buttons wrap instead of
 * breaking their words.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VisitTimePicker(time: LocalTime?, onTime: (LocalTime) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val initial = time ?: DEFAULT_PICKER_TIME
    val pickerState = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    val largeText = isLargeText()
    TimePickerDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start time") },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                if (time != null && !largeText) TextButton(onClick = onClear) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onTime(LocalTime.of(pickerState.hour, pickerState.minute)) }) { Text("OK") }
            }
        },
    ) {
        if (largeText) TimeInput(state = pickerState) else TimePicker(state = pickerState)
    }
}

/** Under an imported ticket not saved yet (spec §6.10). */
internal const val DRAFT_SAVED_WITH_VISIT = "From the imported ticket: saved with the visit."
internal const val DRAFT_QR_KEPT = "QR code read from the ticket."
internal const val DRAFT_QR_NONE = "No QR code could be read from the ticket. The booking number is kept."
internal const val DRAFT_QR_EXPIRED = "The QR code isn't kept: the app removes it 7 days after the showing."
internal const val DRAFT_NO_SEATS = "The seats couldn't be read from the ticket."

/** The heading of the import's notes (spec §6.10). */
internal const val IMPORT_NOTES_TITLE = "Fill in by hand"

/**
 * What the ticket import couldn't read or match (spec §6.10), each line with what was
 * printed, e.g. "Hall on the ticket: Saal 9 (not in the app)". Not stored.
 */
@Composable
internal fun ImportNotes(notes: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CinexTheme.colors.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            IMPORT_NOTES_TITLE,
            style = MaterialTheme.typography.labelMedium,
            color = CinexTheme.colors.textMuted,
            modifier = Modifier.semantics { heading() },
        )
        notes.forEach { note ->
            Text(note, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text)
        }
    }
}

/**
 * The Ticket section (spec §6.4) of a visit with a stored [ticket]: booking number,
 * every seat of the booking, "Show ticket" (§6.11) and "Remove ticket" (asks first).
 * A ticket whose seats couldn't be read shows the visit's own seat ([ownSeat]) as
 * "Your seat", or no seat at all.
 */
@Composable
internal fun TicketSection(ticket: Ticket, ownSeat: SeatLabel?, removing: Boolean, enabled: Boolean, actions: VisitEditActions) {
    TicketSectionContent(
        ticket.bookingNumber, ticket.seats, ownSeat = ownSeat, draftQrCode = null, removing = removing, enabled = enabled, actions = actions,
    )
}

/**
 * The Ticket section of an imported ticket not saved yet ([draft], spec §6.10): booking
 * number, the seats as printed, whether its QR code is kept, and "Remove ticket"
 * (no confirmation: nothing is stored yet). "Show ticket" comes once it is saved.
 */
@Composable
internal fun TicketDraftSection(draft: TicketDraft, qrCode: DraftQrCode?, enabled: Boolean, actions: VisitEditActions) {
    TicketSectionContent(
        draft.bookingNumber, draft.seats, ownSeat = null, draftQrCode = qrCode ?: DraftQrCode.NONE, removing = false, enabled = enabled,
        actions = actions,
    )
}

/** A small heading over seat texts, e.g. "Seats of the booking", "Row 7 · Seat 10". */
@Composable
private fun SeatLines(title: String, labels: List<SeatLabel>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = CinexTheme.colors.textMuted)
        labels.forEach { label ->
            Text(
                text = label.shortText,
                style = MaterialTheme.typography.bodyMedium,
                color = CinexTheme.colors.text,
                // "Row 7, Seat 10" instead of "Row 7 · Seat 10".
                modifier = Modifier.semantics { contentDescription = label.displayText },
            )
        }
    }
}

/** [draftQrCode] `null`: a stored ticket; [ownSeat] is shown only for a stored ticket without seats. */
@Composable
private fun TicketSectionContent(
    bookingNumber: BookingNumber,
    seats: List<TicketSeat>,
    ownSeat: SeatLabel?,
    draftQrCode: DraftQrCode?,
    removing: Boolean,
    enabled: Boolean,
    actions: VisitEditActions,
) {
    val isDraft = draftQrCode != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Ticket")
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CinexTheme.colors.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val number = bookingNumber.value
            Column(
                Modifier.clearAndSetSemantics { contentDescription = "Booking number, ${spelledOut(number)}" },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Booking number", style = MaterialTheme.typography.labelMedium, color = CinexTheme.colors.textMuted)
                // One line, never broken inside the number: a long one shrinks to fit.
                val style = CinexTheme.textStyles.cardTitle.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize)
                Text(
                    text = number,
                    style = style,
                    color = CinexTheme.colors.text,
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = style.fontSize),
                )
            }
            when {
                seats.isNotEmpty() -> SeatLines(if (seats.size == 1) "Seat of the booking" else "Seats of the booking", seats.map { it.label })
                // The ticket's seats couldn't be read: it is saved without them.
                isDraft -> Text(DRAFT_NO_SEATS, style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
                ownSeat != null -> SeatLines("Your seat", listOf(ownSeat))
                else -> Unit // no seat lines, and no empty heading
            }
            if (draftQrCode != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val qrText = when (draftQrCode) {
                        DraftQrCode.KEPT -> DRAFT_QR_KEPT
                        DraftQrCode.NONE -> DRAFT_QR_NONE
                        DraftQrCode.EXPIRED -> DRAFT_QR_EXPIRED
                    }
                    Text(qrText, style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.text)
                    Text(DRAFT_SAVED_WITH_VISIT, style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                // Neither while the visit is saved or deleted, nor while the ticket is removed.
                val canAct = enabled && !removing
                // Only a stored ticket can be shown: an imported one is stored on Save.
                if (!isDraft) OutlinedPillButton(text = "Show ticket", onClick = actions::onShowTicket, icon = null, enabled = canAct)
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = canAct, role = Role.Button, onClick = actions::onRemoveTicket)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // Muted like "Remove seat": the quieter, destructive action.
                    Text("Remove ticket", style = MaterialTheme.typography.labelMedium, color = CinexTheme.colors.textMuted)
                }
            }
        }
    }
}
