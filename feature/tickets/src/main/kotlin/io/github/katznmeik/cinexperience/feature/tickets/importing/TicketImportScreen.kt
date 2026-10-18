package io.github.katznmeik.cinexperience.feature.tickets.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.model.VisitPrefillArgs
import io.github.katznmeik.cinexperience.core.ui.component.MutedText
import io.github.katznmeik.cinexperience.core.ui.component.OutlinedPillButton
import io.github.katznmeik.cinexperience.core.ui.component.TopBarIconButton
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme

/** Shown while the ticket is read. */
internal const val READING_TICKET = "Reading ticket…"

/** Under every import error: the import never stores anything it couldn't finish. */
internal const val NOTHING_SAVED = "Nothing was saved."

/**
 * The import screen (spec §6.10): "Reading ticket…" until the ticket is read, then it
 * hands over once to Add visit ([onOpenNewVisit]) or to the visit that has the ticket
 * already ([onOpenImportedVisit]), both replacing this screen; an error stays here
 * with a way back. Back always works, also while a slow app still delivers the file.
 */
@Composable
internal fun TicketImportEntry(
    viewModel: TicketImportViewModel,
    onBack: () -> Unit,
    onOpenNewVisit: (hallId: Long?, prefill: VisitPrefillArgs) -> Unit,
    onOpenImportedVisit: (visitId: Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openNew by rememberUpdatedState(onOpenNewVisit)
    val openImported by rememberUpdatedState(onOpenImportedVisit)
    // Handed over once, also if the screen is recreated while it leaves.
    var handedOver by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (handedOver) return@LaunchedEffect
        when (val current = state) {
            is TicketImportState.OpenNewVisit -> {
                handedOver = true
                openNew(current.match.hallId, current.match.prefill)
            }
            is TicketImportState.OpenImportedVisit -> {
                handedOver = true
                openImported(current.visitId)
            }
            else -> Unit
        }
    }
    TicketImportScreen(state, onBack)
}

/** The import screen, stateless (see [TicketImportEntry]). */
@Composable
internal fun TicketImportScreen(state: TicketImportState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopBarIconButton(icon = CinexIcons.Back, contentDescription = "Back", onClick = onBack)
            Text(
                text = "IMPORT TICKET",
                style = MaterialTheme.typography.titleLarge,
                color = CinexTheme.colors.text,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .semantics { heading() },
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                is TicketImportState.Failed -> {
                    Text(state.error.message, style = MaterialTheme.typography.bodyLarge, color = CinexTheme.colors.text)
                    MutedText(NOTHING_SAVED)
                    OutlinedPillButton(text = "Back", onClick = onBack, icon = null)
                }
                // Also while it hands over to the visit form.
                else -> {
                    Text(READING_TICKET, style = MaterialTheme.typography.bodyLarge, color = CinexTheme.colors.text)
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = CinexTheme.colors.text,
                        trackColor = CinexTheme.colors.outline,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                    )
                }
            }
        }
    }
}
