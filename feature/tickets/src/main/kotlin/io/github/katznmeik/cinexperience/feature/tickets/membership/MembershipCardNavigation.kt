package io.github.katznmeik.cinexperience.feature.tickets.membership

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.katznmeik.cinexperience.core.data.settings.MembershipCardStore
import io.github.katznmeik.cinexperience.core.ui.component.ScreenBackground
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketFileSource
import kotlinx.serialization.Serializable

/**
 * The membership card with its QR code (v1.5, spec §6.12), opened from Settings →
 * Data → Membership card and from the ticket (§6.11).
 */
@Serializable
data object MembershipCardRoute

/** Add or edit the membership card (spec §6.13). */
@Serializable
data object EditMembershipCardRoute

fun NavController.navigateToMembershipCard() = navigate(MembershipCardRoute)

fun NavController.navigateToEditMembershipCard() = navigate(EditMembershipCardRoute)

/**
 * The membership card screens (spec §6.12, §6.13).
 *
 * @param fileSource opens the screenshot the photo picker returned (a `content:` URI of
 *   another app; [io.github.katznmeik.cinexperience.feature.tickets.importing.ContentTicketFileSource] in the app).
 * @param reader reads its QR code ([DefaultCardImageReader] in the app).
 * @param onEdit opens [EditMembershipCardRoute] (also "Add membership card").
 */
fun NavGraphBuilder.membershipCardScreens(
    store: MembershipCardStore,
    fileSource: TicketFileSource,
    reader: CardImageReader,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    composable<MembershipCardRoute> {
        ScreenBackground {
            MembershipCardEntry(
                viewModel = viewModel(factory = MembershipCardViewModel.factory(store)),
                onBack = dropUnlessResumed(block = onBack),
                onEdit = dropUnlessResumed(block = onEdit),
            )
        }
    }
    composable<EditMembershipCardRoute> {
        ScreenBackground {
            EditMembershipCardEntry(
                viewModel = viewModel(factory = EditMembershipCardViewModel.factory(store, fileSource, reader)),
                // Not dropped unless resumed: after Save the form closes itself while it may be only started.
                onClose = onBack,
                onDiscard = dropUnlessResumed(block = onBack),
            )
        }
    }
}
