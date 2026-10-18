package io.github.katznmeik.cinexperience.feature.tickets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.ui.component.LabelChip
import io.github.katznmeik.cinexperience.core.ui.component.MutedText
import io.github.katznmeik.cinexperience.core.ui.component.TopBarIconButton
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.text.dayHeaderText
import io.github.katznmeik.cinexperience.core.ui.text.isolate
import io.github.katznmeik.cinexperience.core.ui.text.spelledOut
import io.github.katznmeik.cinexperience.core.ui.text.timeText
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

@Composable
internal fun TicketEntry(viewModel: TicketViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TicketScreen(state, onBack)
}

/** Shown without a ticket (or without its visit). */
internal const val TICKET_NOT_FOUND = "This ticket no longer exists."

/** Shown when the QR code was removed after the showing (spec §4.7). */
internal const val QR_REMOVED_NOTE =
    "The QR code was removed: it is kept until 7 days after the showing. The booking number stays."

/** Shown when no QR code is stored although it would still be kept, e.g. after a backup was restored. */
internal const val QR_NOT_STORED_NOTE =
    "This ticket has no QR code in the app, for example after a backup was restored (backups don't include QR codes). " +
        "Import the ticket again to show it."

/** Space between the top bar and the QR code. */
private val BODY_TOP_PADDING = 8.dp

/** Space kept below the QR code when the height limits its size (landscape). */
private val QR_BOTTOM_ROOM = 8.dp

/**
 * "Thursday 15 Oct · 20:15", "Today · Thursday 15 Oct · 20:15", with the year when
 * it isn't [TicketDetails.today]'s ("Friday 1 Jan 2027 · 20:15"); without a start
 * time only the day.
 */
internal fun TicketDetails.showingText(): String {
    val day = dayHeaderText(date, today).let { if (date.year != today.year) "$it ${date.year}" else it }
    return listOfNotNull(day, startTime?.let(::timeText)).joinToString(" · ")
}

/** "delphi LUX · Saal 4"; `null` if the hall can't be read. */
internal fun TicketDetails.placeText(): String? =
    if (cinemaName == null || hallName == null) null else "$cinemaName · $hallName"

/**
 * The ticket (spec §6.11): the QR code as large as fits, black on white, at full
 * brightness with the screen kept on while it is shown; below it the film, day and
 * start time, cinema · hall, all seats (the visit's own one marked) and the booking
 * number in large type. Without a QR code the booking number comes first, with a
 * note why. Everything but the QR panel stays dark (spec §7).
 */
@Composable
internal fun TicketScreen(state: TicketContent, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        TicketTopBar(onBack)
        when (state) {
            TicketContent.Loading -> Unit
            TicketContent.NotFound -> MutedText(TICKET_NOT_FOUND, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            is TicketContent.Error -> MutedText(state.message, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            is TicketContent.Shown -> TicketBody(state.ticket)
        }
    }
}

@Composable
private fun TicketTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarIconButton(icon = CinexIcons.Back, contentDescription = "Back", onClick = onBack)
        Text(
            text = "TICKET",
            style = MaterialTheme.typography.titleLarge,
            color = CinexTheme.colors.text,
            modifier = Modifier
                .padding(start = 4.dp)
                .semantics { heading() },
        )
    }
}

@Composable
private fun TicketBody(ticket: TicketDetails) {
    // The height below the top bar: the QR code is never taller than that, so it always fits (landscape).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val qrMaxSide = maxHeight - BODY_TOP_PADDING - QR_BOTTOM_ROOM
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = BODY_TOP_PADDING, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val qr = ticket.qrModules
            if (qr != null) {
                KeepScreenBrightAndOn()
                QrCodePanel(qr, maxSide = qrMaxSide)
                ShowingDetails(ticket)
                BookingNumber(ticket)
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(CinexTheme.colors.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BookingNumber(ticket)
                    MutedText(
                        text = if (ticket.missingQrCode == MissingQrCode.NOT_STORED) QR_NOT_STORED_NOTE else QR_REMOVED_NOTE,
                        modifier = Modifier.padding(horizontal = 4.dp), // aligned with the booking number
                    )
                }
                ShowingDetails(ticket)
            }
        }
    }
}

@Composable
private fun ShowingDetails(ticket: TicketDetails) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            // Isolated: a title from Yorck may hold right-to-left text.
            text = ticket.filmTitle?.let(::isolate)?.uppercase() ?: "UNTITLED",
            style = CinexTheme.textStyles.cardTitle.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize),
            color = if (ticket.filmTitle == null) CinexTheme.colors.textMuted else CinexTheme.colors.text,
        )
        Text(ticket.showingText(), style = MaterialTheme.typography.bodyLarge, color = CinexTheme.colors.text)
        ticket.placeText()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text) }
    }
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (ticket.seats.size == 1) "Seat" else "Seats",
            style = MaterialTheme.typography.labelMedium,
            color = CinexTheme.colors.textMuted,
        )
        ticket.seats.forEach { seat ->
            Row(
                // "Row 7, Seat 10, your seat": one announcement per seat.
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = if (seat.isOwn) "${seat.label.displayText}, your seat" else seat.label.displayText
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(seat.label.shortText, style = MaterialTheme.typography.bodyLarge, color = CinexTheme.colors.text)
                if (seat.isOwn) LabelChip("Your seat")
            }
        }
    }
}

/** The smallest size the booking number shrinks to so it stays on one line (a 16-character one at 2× font). */
internal val BOOKING_NUMBER_MIN_SIZE = 14.sp

@Composable
private fun BookingNumber(ticket: TicketDetails) {
    val number = ticket.bookingNumber.value
    val style = CinexTheme.textStyles.scoreLarge.copy(letterSpacing = 2.sp)
    Column(
        Modifier
            .padding(horizontal = 4.dp)
            .clearAndSetSemantics { contentDescription = "Booking number, ${spelledOut(number)}" },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("Booking number", style = MaterialTheme.typography.labelMedium, color = CinexTheme.colors.textMuted)
        // One line, never broken inside the number: a long one shrinks to fit.
        Text(
            text = number,
            style = style,
            color = CinexTheme.colors.text,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = BOOKING_NUMBER_MIN_SIZE, maxFontSize = style.fontSize),
        )
    }
}
