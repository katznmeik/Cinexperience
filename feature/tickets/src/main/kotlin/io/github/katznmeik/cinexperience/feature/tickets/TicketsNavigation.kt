package io.github.katznmeik.cinexperience.feature.tickets

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.katznmeik.cinexperience.core.data.repository.HallRepository
import io.github.katznmeik.cinexperience.core.data.repository.TicketRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.ui.component.ScreenBackground
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
