package io.github.katznmeik.cinexperience.feature.cinemas.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import io.github.katznmeik.cinexperience.core.ui.component.OutlinedPillButton
import io.github.katznmeik.cinexperience.core.ui.text.isolate
import io.github.katznmeik.cinexperience.core.ui.text.showingDayText
import io.github.katznmeik.cinexperience.core.ui.text.timeText
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** "delphi LUX · Saal 4" */
internal fun NextVisit.placeText(): String = "$cinemaName · $hallName"

/** "Tomorrow · 20:15", "Sat 17 Oct · 20:15", "Today" (Berlin time); once the showing has started "Started 20:15". */
internal fun NextVisit.whenText(): String {
    val start = startTime
    return if (hasStarted && start != null) "Started ${timeText(start)}" else showingDayText(date, start, today)
}

/** What screen readers announce for the card (it opens the visit). */
internal fun NextVisit.description(): String = listOfNotNull(
    "Next visit: ${filmTitle?.let(::isolate) ?: "Untitled"}",
    whenText(),
    placeText(),
    seatLabel?.description,
).joinToString(", ")

/**
 * The Next visit card (spec §6.1): film, day and start time ("Started 20:15" for up
 * to 30 minutes after the start), cinema · hall and seat of the soonest such visit;
 * tapping it opens the visit. "Show ticket" opens the ticket screen (§6.11) when
 * the visit has a QR code.
 */
@Composable
internal fun NextVisitCard(next: NextVisit, onOpenVisit: () -> Unit, onShowTicket: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CinexTheme.colors.surface),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpenVisit)
                // One announcement for the card: "Next visit: …, Tomorrow · 20:15, delphi LUX · Saal 4, Row 7, Seat 10".
                .clearAndSetSemantics {
                    contentDescription = next.description()
                    role = Role.Button
                    onClick {
                        onOpenVisit()
                        true
                    }
                }
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (next.canShowTicket) 4.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("NEXT VISIT", style = CinexTheme.textStyles.sectionHeader, color = CinexTheme.colors.textMuted)
            Text(
                // Isolated: a title from Yorck may hold right-to-left text (the card is read as one).
                text = next.filmTitle?.let(::isolate)?.uppercase() ?: "UNTITLED",
                style = CinexTheme.textStyles.cardTitle,
                color = if (next.filmTitle == null) CinexTheme.colors.textMuted else CinexTheme.colors.text,
            )
            Text(next.whenText(), style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text)
            Text(next.placeText(), style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
            next.seatLabel?.let { seat ->
                Text(seat.shortText, style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
            }
        }
        if (next.canShowTicket) {
            OutlinedPillButton(
                text = "Show ticket",
                onClick = onShowTicket,
                icon = null,
                modifier = Modifier.padding(start = 10.dp, bottom = 6.dp),
            )
        }
    }
}
