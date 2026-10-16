package io.github.katznmeik.cinexperience.feature.cinemas.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.model.HallSummary
import io.github.katznmeik.cinexperience.core.ui.component.BackTopBar
import io.github.katznmeik.cinexperience.core.ui.component.OutlinedPillButton
import io.github.katznmeik.cinexperience.core.ui.component.RowDivider
import io.github.katznmeik.cinexperience.core.ui.component.RowGroup
import io.github.katznmeik.cinexperience.core.ui.component.ScreenTitle
import io.github.katznmeik.cinexperience.core.ui.component.TopBarIconButton
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.text.countText
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.cinemas.detail.hallMenuEntries
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditDialogActions
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditDialogHost
import io.github.katznmeik.cinexperience.feature.cinemas.edit.ItemMenu
import io.github.katznmeik.cinexperience.feature.cinemas.edit.MenuEntry
import io.github.katznmeik.cinexperience.feature.cinemas.manageHallMeta
import io.github.katznmeik.cinexperience.feature.cinemas.shortAddress

@Composable
internal fun ManageCinemasEntry(
    viewModel: ManageCinemasViewModel,
    onBack: () -> Unit,
    onOpenCinema: (Long) -> Unit,
    message: String?,
    onMessageShown: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        object : EditDialogActions {
            override fun onNameChange(name: String) = viewModel.onNameChange(name)
            override fun onAddressChange(address: String) = viewModel.onAddressChange(address)
            override fun onNotesChange(notes: String) = viewModel.onNotesChange(notes)
            override fun onConfirm() = viewModel.confirmDialog()
            override fun onDismiss() = viewModel.dismissDialog()
            override fun onDeleteCinema() = viewModel.deleteFromForm()
        }
    }
    ManageCinemasScreen(
        state = state,
        onBack = onBack,
        onOpenCinema = onOpenCinema,
        onAddCinema = viewModel::addCinema,
        onEditCinema = viewModel::editCinema,
        onDeleteCinema = viewModel::deleteCinema,
        onAddHall = viewModel::addHall,
        onRenameHall = viewModel::renameHall,
        onDeleteHall = viewModel::deleteHall,
        dialogActions = actions,
        onMessageShown = viewModel::messageShown,
        incomingMessage = message,
        onIncomingMessageShown = onMessageShown,
    )
}

/** Manage cinemas & halls (spec §6.7, Data): add, edit / rename, delete user-added ones. */
@Composable
internal fun ManageCinemasScreen(
    state: ManageCinemasUiState,
    onBack: () -> Unit,
    onOpenCinema: (Long) -> Unit,
    onAddCinema: () -> Unit,
    onEditCinema: (CinemaDetail) -> Unit,
    onDeleteCinema: (CinemaDetail) -> Unit,
    onAddHall: (Long) -> Unit,
    onRenameHall: (HallSummary) -> Unit,
    onDeleteHall: (HallSummary) -> Unit,
    dialogActions: EditDialogActions,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
    incomingMessage: String? = null,
    onIncomingMessageShown: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(incomingMessage) {
        incomingMessage?.let {
            snackbarHostState.showSnackbar(it)
            onIncomingMessageShown()
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            onMessageShown()
        }
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "top") { BackTopBar(onBack) }
            item(key = "title") {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ScreenTitle("Cinemas & halls")
                    if (!state.isLoading) {
                        Text(
                            text = "${countText(state.cinemas.size, "cinema")} · ${countText(state.hallCount, "hall")} · " +
                                "${countText(state.layoutsDrawn, "layout")} drawn",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CinexTheme.colors.textMuted,
                        )
                    }
                    Text(
                        text = "Preloaded Yorck cinemas and halls can be renamed but not deleted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinexTheme.colors.textMuted,
                    )
                }
            }
            item(key = "add") {
                OutlinedPillButton(
                    text = "Add cinema",
                    onClick = onAddCinema,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            state.errorMessage?.let { error ->
                item(key = "error") {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinexTheme.colors.textMuted,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            items(state.cinemas, key = { it.cinema.id }) { cinema ->
                RowGroup(Modifier.padding(horizontal = 16.dp)) {
                    CinemaRow(
                        cinema = cinema,
                        onOpen = { onOpenCinema(cinema.cinema.id) },
                        menu = cinemaMenuEntries(
                            cinema,
                            onEdit = { onEditCinema(cinema) },
                            onAddHall = { onAddHall(cinema.cinema.id) },
                            onDelete = { onDeleteCinema(cinema) },
                        ),
                    )
                    cinema.halls.forEach { hall ->
                        RowDivider()
                        HallRow(hall, hallMenuEntries(hall, { onRenameHall(hall) }, { onDeleteHall(hall) }))
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
    EditDialogHost(state.dialog, dialogActions)
}

private fun cinemaMenuEntries(
    cinema: CinemaDetail,
    onEdit: () -> Unit,
    onAddHall: () -> Unit,
    onDelete: () -> Unit,
): List<MenuEntry> = listOf(
    MenuEntry("Edit", onClick = onEdit),
    MenuEntry("Add hall", onClick = onAddHall),
    if (cinema.cinema.isPreloaded) {
        MenuEntry("Delete", explanation = "Preloaded cinemas can't be deleted", enabled = false)
    } else {
        MenuEntry("Delete", onClick = onDelete)
    },
)

@Composable
private fun CinemaRow(cinema: CinemaDetail, onOpen: () -> Unit, menu: List<MenuEntry>) {
    val meta = listOfNotNull(
        if (cinema.cinema.isPreloaded) "Preloaded" else "Added by you",
        cinema.cinema.shortAddress,
    ).joinToString(" · ")
    ManageRow(
        title = cinema.cinema.name.uppercase(),
        subtitle = meta,
        isCinema = true,
        onClick = onOpen,
        menu = menu,
        menuDescription = "Actions for ${cinema.cinema.name}",
    )
}

@Composable
private fun HallRow(hall: HallSummary, menu: List<MenuEntry>) {
    ManageRow(
        title = hall.hall.name,
        subtitle = manageHallMeta(hall),
        isCinema = false,
        onClick = null,
        menu = menu,
        menuDescription = "Actions for ${hall.hall.name}",
    )
}

/** A cinema or (indented) hall row with an overflow menu. Without [onClick], a tap opens the menu. */
@Composable
private fun ManageRow(
    title: String,
    subtitle: String,
    isCinema: Boolean,
    onClick: (() -> Unit)?,
    menu: List<MenuEntry>,
    menuDescription: String,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick ?: { menuOpen = true })
            .padding(start = if (isCinema) 16.dp else 28.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = if (isCinema) {
                    CinexTheme.textStyles.cardTitle
                } else {
                    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                },
                color = CinexTheme.colors.text,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
        }
        Box {
            TopBarIconButton(
                icon = CinexIcons.More,
                contentDescription = menuDescription,
                onClick = { menuOpen = true },
                tint = CinexTheme.colors.textMuted,
            )
            ItemMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, entries = menu)
        }
    }
}
