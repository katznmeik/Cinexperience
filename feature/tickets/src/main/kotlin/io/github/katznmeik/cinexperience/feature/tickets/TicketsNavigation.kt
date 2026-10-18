package io.github.katznmeik.cinexperience.feature.tickets

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.katznmeik.cinexperience.core.data.repository.CinemaRepository
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.data.repository.TicketRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.model.VisitPrefillArgs
import io.github.katznmeik.cinexperience.core.program.ProgramRepository
import io.github.katznmeik.cinexperience.core.ui.component.ScreenBackground
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketFileSource
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketImportEntry
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketImportViewModel
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketReader
import kotlinx.serialization.Serializable
import java.time.Clock

/**
 * The ticket of visit [visitId] with its QR code (spec §6.11), opened from the Next
 * visit card (§6.1), the visit's Ticket section (§6.4) and, later, the showtime
 * reminder (§11.3).
 */
@Serializable
data class TicketRoute(val visitId: Long)

fun NavController.navigateToTicket(visitId: Long) = navigate(TicketRoute(visitId))

/**
 * Import the ticket PDF at [uri] (spec §6.10): from "Share" or "Open with" (see
 * `TicketIntents`) or Settings → Data → Import ticket. [uri] is a `content:` URI as
 * text; `null` (e.g. a share without a file) shows the "can't open" message. The
 * screen replaces itself with Add visit, or with the visit that has the ticket
 * already, through the callbacks of [ticketImportScreen].
 */
@Serializable
data class ImportTicketRoute(val uri: String? = null)

fun NavController.navigateToImportTicket(uri: String?) = navigate(ImportTicketRoute(uri))

/**
 * @param clock "now" for the QR code's expiry (7 days after the showing) and "Today".
 */
fun NavGraphBuilder.ticketScreen(
    visitRepository: VisitRepository,
    hallRepository: HallRepository,
    ticketRepository: TicketRepository,
    clock: Clock,
    onBack: () -> Unit,
) {
    composable<TicketRoute> { entry ->
        val visitId = entry.toRoute<TicketRoute>().visitId
        ScreenBackground {
            TicketEntry(
                viewModel = viewModel(
                    factory = TicketViewModel.factory(visitId, visitRepository, hallRepository, ticketRepository, clock),
                ),
                onBack = dropUnlessResumed(block = onBack),
            )
        }
    }
}

/**
 * The ticket import (spec §6.10, §11.2). Nothing is stored here, except the QR code of
 * a ticket imported before whose code had been removed.
 *
 * @param fileSource opens the file ([io.github.katznmeik.cinexperience.feature.tickets.importing.ContentTicketFileSource]
 *   in the app).
 * @param reader reads the PDF ([io.github.katznmeik.cinexperience.feature.tickets.importing.DefaultTicketReader] in the app).
 * @param programRepository only its state is read (the program already on the phone): the import never downloads.
 * @param clock "now" for the QR code's expiry.
 * @param onOpenNewVisit opens Add visit with the matched ticket, for [hallId] if the hall
 *   was matched; it replaces the import screen.
 * @param onOpenImportedVisit opens the visit that has this ticket already, with "This
 *   ticket was imported before"; it replaces the import screen.
 */
fun NavGraphBuilder.ticketImportScreen(
    cinemaRepository: CinemaRepository,
    visitRepository: VisitRepository,
    ticketRepository: TicketRepository,
    programRepository: ProgramRepository,
    fileSource: TicketFileSource,
    reader: TicketReader,
    clock: Clock,
    onBack: () -> Unit,
    onOpenNewVisit: (hallId: Long?, prefill: VisitPrefillArgs) -> Unit,
    onOpenImportedVisit: (visitId: Long) -> Unit,
) {
    composable<ImportTicketRoute> { entry ->
        val uri = entry.toRoute<ImportTicketRoute>().uri
        ScreenBackground {
            TicketImportEntry(
                viewModel = viewModel(
                    factory = TicketImportViewModel.factory(
                        uri, fileSource, reader, cinemaRepository, visitRepository, ticketRepository, programRepository, clock,
                    ),
                ),
                // Back always works, also while the file is still being read.
                onBack = dropUnlessResumed(block = onBack),
                onOpenNewVisit = onOpenNewVisit,
                onOpenImportedVisit = onOpenImportedVisit,
            )
        }
    }
}
