package io.github.katznmeik.cinexperience.feature.tickets.membership

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.model.MembershipCard
import io.github.katznmeik.cinexperience.core.ui.component.MessageSnackbarHost
import io.github.katznmeik.cinexperience.core.ui.component.MutedText
import io.github.katznmeik.cinexperience.core.ui.component.OutlinedPillButton
import io.github.katznmeik.cinexperience.core.ui.component.TopBarIconButton
import io.github.katznmeik.cinexperience.core.ui.component.rememberMessageSnackbarState
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.text.spelledOut
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.tickets.KeepScreenBrightAndOn
import io.github.katznmeik.cinexperience.feature.tickets.QrCodePanel

/** Read by screen readers for the card's QR code. */
internal const val CARD_QR_DESCRIPTION = "Membership card QR code"

/** Shown while no card is stored (spec §6.12). */
internal const val NO_CARD_TEXT =
    "No membership card yet. Take a screenshot of your card on yorck.de (Konto → Übersicht) and add it here: " +
        "the app shows its QR code for the entrance."

/** The text of the "Remove card" confirmation. */
internal const val REMOVE_CARD_TEXT = "The card is removed from this phone. You can add the screenshot again any time."

/** Space kept below the QR code when the height limits its size (landscape). */
private val QR_BOTTOM_ROOM = 8.dp

/** Space between the top bar and the QR code. */
private val BODY_TOP_PADDING = 8.dp

@Composable
internal fun MembershipCardEntry(viewModel: MembershipCardViewModel, onBack: () -> Unit, onEdit: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MembershipCardScreen(
        state = state,
        onBack = onBack,
        onEdit = onEdit,
        onRemove = viewModel::onRemove,
        onConfirmRemove = viewModel::onConfirmRemove,
        onCancelRemove = viewModel::onCancelRemove,
        onMessageShown = viewModel::onMessageShown,
    )
}

/**
 * The membership card (spec §6.12): the QR code as large as fits, black on white, at
 * full brightness with the screen kept on while it is shown, like the ticket; below
 * it the name, the membership number in large type and the membership type, then
 * "Remove card". Without a card: the explanation and "Add membership card".
 */
@Composable
internal fun MembershipCardScreen(
    state: MembershipCardUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit = {},
    onRemove: () -> Unit = {},
    onConfirmRemove: () -> Unit = {},
    onCancelRemove: () -> Unit = {},
    onMessageShown: () -> Unit = {},
) {
    val snackbar = rememberMessageSnackbarState(state.message, onMessageShown)
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            CardTopBar(onBack, onEdit = onEdit.takeIf { state.content is MembershipCardContent.Shown })
            when (val content = state.content) {
                MembershipCardContent.Loading -> Unit
                is MembershipCardContent.Error -> MutedText(content.message, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                MembershipCardContent.None -> NoCard(onAdd = onEdit)
                is MembershipCardContent.Shown -> CardBody(content.card, removing = state.isRemoving, onRemove = onRemove)
            }
        }
        MessageSnackbarHost(snackbar)
    }
    if (state.confirmRemove) {
        AlertDialog(
            onDismissRequest = onCancelRemove,
            title = { Text("Remove the membership card?") },
            text = { MutedText(REMOVE_CARD_TEXT) },
            confirmButton = { TextButton(onClick = onConfirmRemove) { Text("Remove") } },
            dismissButton = { TextButton(onClick = onCancelRemove) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CardTopBar(onBack: () -> Unit, onEdit: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarIconButton(icon = CinexIcons.Back, contentDescription = "Back", onClick = onBack)
        Text(
            text = "MEMBERSHIP CARD",
            style = MaterialTheme.typography.titleLarge,
            color = CinexTheme.colors.text,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
                .semantics { heading() },
        )
        if (onEdit != null) TopBarIconButton(icon = CinexIcons.Edit, contentDescription = "Edit card", onClick = onEdit)
    }
}

@Composable
private fun NoCard(onAdd: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MutedText(NO_CARD_TEXT)
        OutlinedPillButton(text = "Add membership card", onClick = onAdd)
    }
}

@Composable
private fun CardBody(card: MembershipCard, removing: Boolean, onRemove: () -> Unit) {
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
            KeepScreenBrightAndOn()
            QrCodePanel(card.qrModules, maxSide = qrMaxSide, description = CARD_QR_DESCRIPTION)
            CardDetails(card)
            TextButton(onClick = onRemove, enabled = !removing) {
                Text("Remove card", style = MaterialTheme.typography.labelLarge, color = CinexTheme.colors.textMuted)
            }
        }
    }
}

/** The smallest size a long membership number shrinks to. */
private val MEMBER_NUMBER_MIN_SIZE = 14.sp

@Composable
private fun CardDetails(card: MembershipCard) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        card.name?.let { name ->
            Text(
                text = name,
                style = CinexTheme.textStyles.cardTitle.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize),
                color = CinexTheme.colors.text,
            )
        }
        card.memberNumber?.let { number ->
            val style = CinexTheme.textStyles.scoreLarge.copy(letterSpacing = 2.sp)
            Column(
                Modifier.clearAndSetSemantics { contentDescription = "Membership number, ${spelledOut(number)}" },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Membership number", style = MaterialTheme.typography.labelMedium, color = CinexTheme.colors.textMuted)
                // A long number shrinks first, then takes a second line: it is never cut off.
                Text(
                    text = number,
                    style = style,
                    color = CinexTheme.colors.text,
                    maxLines = 2,
                    autoSize = TextAutoSize.StepBased(minFontSize = MEMBER_NUMBER_MIN_SIZE, maxFontSize = style.fontSize),
                )
            }
        }
        card.membershipType?.let { type ->
            Column(
                Modifier.clearAndSetSemantics { contentDescription = "Membership, $type" },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Membership", style = MaterialTheme.typography.labelMedium, color = CinexTheme.colors.textMuted)
                Text(type, style = MaterialTheme.typography.bodyLarge, color = CinexTheme.colors.text)
            }
        }
    }
}
