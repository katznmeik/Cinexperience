package io.github.katznmeik.cinexperience.feature.tickets.membership

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.ui.component.MessageSnackbarHost
import io.github.katznmeik.cinexperience.core.ui.component.MutedText
import io.github.katznmeik.cinexperience.core.ui.component.OutlinedPillButton
import io.github.katznmeik.cinexperience.core.ui.component.SectionHeader
import io.github.katznmeik.cinexperience.core.ui.component.TopBarIconButton
import io.github.katznmeik.cinexperience.core.ui.component.isLargeText
import io.github.katznmeik.cinexperience.core.ui.component.rememberMessageSnackbarState
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.navigation.CloseOnce
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.tickets.QrCodePanel

/** How to get the screenshot (spec §6.13, §11.4). */
internal const val SCREENSHOT_HINT =
    "Take a screenshot of your card on yorck.de (Konto → Übersicht) and choose it here. " +
        "The app reads only the QR code; the screenshot itself isn't kept."

/** Why the texts are typed (spec §6.13: no OCR). */
internal const val FIELDS_HINT =
    "Type these as shown on the card: the app doesn't read text from the screenshot. The card stays on this phone and isn't part of backups."

/** Shown when the photo picker can't be opened. */
internal const val NO_PICKER = "No app to choose images is available."

/** The add / edit form's actions. */
internal interface EditMembershipCardActions {
    fun onClose() {}
    fun onSave() {}
    fun onChooseScreenshot() {}
    fun onName(text: String) {}
    fun onMemberNumber(text: String) {}
    fun onMembershipType(text: String) {}
    fun onMessageShown() {}

    companion object {
        val None = object : EditMembershipCardActions {}
    }
}

@Composable
internal fun EditMembershipCardEntry(viewModel: EditMembershipCardViewModel, onClose: () -> Unit, onDiscard: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CloseOnce(state.isSaved, onClose)
    val currentDiscard by rememberUpdatedState(onDiscard)
    // Android's photo picker (no permission needed); cancelling changes nothing.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.onImageChosen(uri?.toString())
    }
    var pickerMissing by remember { mutableStateOf(false) }
    val actions = remember(viewModel, picker) {
        object : EditMembershipCardActions {
            override fun onClose() = currentDiscard()
            override fun onSave() = viewModel.onSave()
            override fun onChooseScreenshot() {
                try {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (e: ActivityNotFoundException) {
                    pickerMissing = true
                }
            }
            override fun onName(text: String) = viewModel.onName(text)
            override fun onMemberNumber(text: String) = viewModel.onMemberNumber(text)
            override fun onMembershipType(text: String) = viewModel.onMembershipType(text)
            override fun onMessageShown() {
                pickerMissing = false
                viewModel.onMessageShown()
            }
        }
    }
    EditMembershipCardScreen(state.copy(message = if (pickerMissing) NO_PICKER else state.message), actions)
}

/**
 * Add / edit membership card (spec §6.13): the screenshot's QR code (or the stored
 * one), "Choose screenshot", and the typed name, membership number and type. Save
 * needs a QR code; Close discards.
 */
@Composable
internal fun EditMembershipCardScreen(
    state: EditMembershipCardUiState,
    actions: EditMembershipCardActions,
    modifier: Modifier = Modifier,
) {
    val snackbar = rememberMessageSnackbarState(state.message, actions::onMessageShown)
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FormTopBar(title = if (state.isNew) "Add membership card" else "Edit membership card", canSave = state.canSave, actions)
            if (!state.isLoading) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    QrSection(state, actions)
                    FieldsSection(state, actions)
                }
            }
        }
        MessageSnackbarHost(snackbar)
    }
}

/**
 * Close, the title and Save. With large text the title gets its own line below Close
 * and Save, so a word like "MEMBERSHIP" is never broken.
 */
@Composable
private fun FormTopBar(title: String, canSave: Boolean, actions: EditMembershipCardActions) {
    val largeText = isLargeText()
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopBarIconButton(icon = CinexIcons.Close, contentDescription = "Close", onClick = actions::onClose)
            if (largeText) Spacer(Modifier.weight(1f)) else FormTitle(title, Modifier.weight(1f).padding(start = 4.dp))
            Button(
                onClick = actions::onSave,
                enabled = canSave,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CinexTheme.colors.text,
                    contentColor = CinexTheme.colors.onPrimary,
                    disabledContainerColor = CinexTheme.colors.surfaceRaised,
                    disabledContentColor = CinexTheme.colors.textMuted,
                ),
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
                Text("Save", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (largeText) FormTitle(title, Modifier.padding(start = 20.dp, end = 16.dp, bottom = 4.dp))
    }
}

@Composable
private fun FormTitle(title: String, modifier: Modifier) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.titleLarge,
        color = CinexTheme.colors.text,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun QrSection(state: EditMembershipCardUiState, actions: EditMembershipCardActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("QR code")
        val qr = state.qrModules
        if (qr != null) {
            QrCodePanel(qr, maxSide = QR_PREVIEW_SIDE, description = CARD_QR_DESCRIPTION)
        } else {
            MutedText(SCREENSHOT_HINT, Modifier.padding(horizontal = 4.dp))
        }
        if (state.isReading) {
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), color = CinexTheme.colors.text, strokeWidth = 2.dp)
                Text("Reading the screenshot…", style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text)
            }
        } else {
            OutlinedPillButton(
                text = if (qr == null) "Choose screenshot" else "Choose another screenshot",
                onClick = actions::onChooseScreenshot,
                icon = CinexIcons.Image,
                enabled = !state.isSaving,
            )
        }
    }
}

private val QR_PREVIEW_SIDE = 200.dp

@Composable
private fun FieldsSection(state: EditMembershipCardUiState, actions: EditMembershipCardActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("On the card")
        CardTextField(state.name, actions::onName, "Name", KeyboardCapitalization.Words)
        CardTextField(state.memberNumber, actions::onMemberNumber, "Membership number", KeyboardCapitalization.Characters)
        CardTextField(state.membershipType, actions::onMembershipType, "Membership type", KeyboardCapitalization.Words, ImeAction.Done)
        MutedText(FIELDS_HINT, Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun CardTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    capitalization: KeyboardCapitalization,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("$label (optional)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = capitalization, autoCorrectEnabled = false, imeAction = imeAction),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = CinexTheme.colors.fieldOutline,
            focusedBorderColor = CinexTheme.colors.text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
