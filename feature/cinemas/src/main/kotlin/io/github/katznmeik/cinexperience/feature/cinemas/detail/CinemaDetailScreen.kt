package io.github.katznmeik.cinexperience.feature.cinemas.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.katznmeik.cinexperience.core.model.CinemaCategory
import io.github.katznmeik.cinexperience.core.model.CinemaDetail
import io.github.katznmeik.cinexperience.core.model.HallSummary
import io.github.katznmeik.cinexperience.core.model.NO_SCORE_TEXT
import io.github.katznmeik.cinexperience.core.model.formatScore
import io.github.katznmeik.cinexperience.core.program.FilmKind
import io.github.katznmeik.cinexperience.core.ui.component.BackMessageContent
import io.github.katznmeik.cinexperience.core.ui.component.BackTopBar
import io.github.katznmeik.cinexperience.core.ui.component.MessageSnackbarHost
import io.github.katznmeik.cinexperience.core.ui.component.OutlinedPillButton
import io.github.katznmeik.cinexperience.core.ui.component.ScreenTitle
import io.github.katznmeik.cinexperience.core.ui.component.SectionHeader
import io.github.katznmeik.cinexperience.core.ui.component.TopBarIconButton
import io.github.katznmeik.cinexperience.core.ui.component.isLargeText
import io.github.katznmeik.cinexperience.core.ui.component.rememberMessageSnackbarState
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditDialogActions
import io.github.katznmeik.cinexperience.feature.cinemas.edit.EditDialogHost
import io.github.katznmeik.cinexperience.feature.cinemas.edit.ItemMenu
import io.github.katznmeik.cinexperience.feature.cinemas.edit.MenuEntry
import io.github.katznmeik.cinexperience.feature.cinemas.hallMeta
import io.github.katznmeik.cinexperience.feature.cinemas.scoreDescription

@Composable
internal fun CinemaDetailEntry(
    viewModel: CinemaDetailViewModel,
    onBack: () -> Unit,
    onOpenHall: (Long) -> Unit,
    onCinemaDeleted: (message: String) -> Unit,
    onOpenFilm: (FilmKind, String) -> Unit = { _, _ -> },
    onShowAllInProgram: () -> Unit = {},
    onOpenProgram: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CloseWhenResumed(state.deletedMessage, onCinemaDeleted)
    val actions = remember(viewModel) { viewModel.dialogActions() }
    CinemaDetailScreen(
        state = state,
        onBack = onBack,
        onEdit = viewModel::editCinema,
        onToggleFavourite = viewModel::toggleFavourite,
        onAddHall = viewModel::addHall,
        onOpenHall = onOpenHall,
        onRenameHall = viewModel::renameHall,
        onDeleteHall = viewModel::deleteHall,
        dialogActions = actions,
        onMessageShown = viewModel::messageShown,
        onOpenFilm = onOpenFilm,
        onShowAllInProgram = onShowAllInProgram,
        onOpenProgram = onOpenProgram,
    )
}

/**
 * Calls [onClose] with [message] while the screen is resumed: right away, or once it
 * is resumed again if the delete ended while the app was paused or a transition ran.
 * Navigation callbacks are dropped unless the screen is resumed, so calling only
 * once, as soon as the message arrives, could leave the screen open.
 */
@Composable
private fun CloseWhenResumed(message: String?, onClose: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnClose by rememberUpdatedState(onClose)
    LaunchedEffect(message, lifecycleOwner) {
        if (message != null) lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { currentOnClose(message) }
    }
}

private fun CinemaDetailViewModel.dialogActions() = object : EditDialogActions {
    override fun onNameChange(name: String) = this@dialogActions.onNameChange(name)
    override fun onAddressChange(address: String) = this@dialogActions.onAddressChange(address)
    override fun onNotesChange(notes: String) = this@dialogActions.onNotesChange(notes)
    override fun onConfirm() = confirmDialog()
    override fun onDismiss() = dismissDialog()
    override fun onDeleteCinema() = deleteFromForm()
}

/** Cinema detail (spec §6.2). */
@Composable
internal fun CinemaDetailScreen(
    state: CinemaDetailUiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onAddHall: () -> Unit,
    onOpenHall: (Long) -> Unit,
    onRenameHall: (HallSummary) -> Unit,
    onDeleteHall: (HallSummary) -> Unit,
    dialogActions: EditDialogActions,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleFavourite: () -> Unit = {},
    onOpenFilm: (FilmKind, String) -> Unit = { _, _ -> },
    onShowAllInProgram: () -> Unit = {},
    onOpenProgram: () -> Unit = {},
) {
    val snackbarHostState = rememberMessageSnackbarState(state.message, onMessageShown)
    Box(modifier.fillMaxSize()) {
        when (val content = state.content) {
            is CinemaDetailContent.Loaded -> LoadedContent(
                detail = content.detail,
                onBack = onBack,
                onEdit = onEdit,
                onToggleFavourite = onToggleFavourite,
                onAddHall = onAddHall,
                onOpenHall = onOpenHall,
                onRenameHall = onRenameHall,
                onDeleteHall = onDeleteHall,
                nowShowing = state.nowShowing,
                onOpenFilm = onOpenFilm,
                onShowAllInProgram = onShowAllInProgram,
                onOpenProgram = onOpenProgram,
            )
            CinemaDetailContent.Loading -> BackTopBar(onBack)
            CinemaDetailContent.NotFound -> BackMessageContent("This cinema no longer exists.", onBack)
            is CinemaDetailContent.Error -> BackMessageContent(content.message, onBack)
        }
        MessageSnackbarHost(snackbarHostState)
    }
    EditDialogHost(state.dialog, dialogActions)
}

@Composable
private fun LoadedContent(
    detail: CinemaDetail,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onToggleFavourite: () -> Unit,
    onAddHall: () -> Unit,
    onOpenHall: (Long) -> Unit,
    onRenameHall: (HallSummary) -> Unit,
    onDeleteHall: (HallSummary) -> Unit,
    nowShowing: NowShowing,
    onOpenFilm: (FilmKind, String) -> Unit,
    onShowAllInProgram: () -> Unit,
    onOpenProgram: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "top") {
            BackTopBar(onBack) {
                FavouriteToggle(detail.cinema.isFavourite, onToggleFavourite)
                TopBarIconButton(icon = CinexIcons.Edit, contentDescription = "Edit cinema", onClick = onEdit)
            }
        }
        item(key = "title") {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ScreenTitle(detail.cinema.name)
                detail.cinema.address?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.textMuted)
                }
                detail.cinema.notes?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
                }
            }
        }
        item(key = "scores") { ScoreCard(detail, Modifier.padding(horizontal = 16.dp)) }
        item(key = "halls-header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader("Halls", Modifier.weight(1f))
                OutlinedPillButton(text = "Add hall", onClick = onAddHall)
            }
        }
        if (detail.halls.isEmpty()) {
            item(key = "no-halls") {
                Text(
                    "No halls yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinexTheme.colors.textMuted,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        items(detail.halls, key = { it.hall.id }) { hall ->
            HallRow(
                hall = hall,
                onClick = { onOpenHall(hall.hall.id) },
                onRename = { onRenameHall(hall) },
                onDelete = { onDeleteHall(hall) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        nowShowingItems(nowShowing, onOpenFilm, onShowAllInProgram, onOpenProgram)
    }
}

/**
 * The ☆/★ favourite toggle (spec §6.2): a 48 dp toggle button, outline star when
 * off, filled star when on, both in the text colour (monochrome). TalkBack reads
 * "Favourite, checked/not checked".
 */
@Composable
private fun FavouriteToggle(favourite: Boolean, onToggle: () -> Unit) {
    IconToggleButton(checked = favourite, onCheckedChange = { onToggle() }) {
        Icon(
            imageVector = if (favourite) CinexIcons.Star else CinexIcons.StarOutline,
            contentDescription = "Favourite",
            tint = CinexTheme.colors.text,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun ScoreCard(detail: CinemaDetail, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(16.dp))
            .background(CinexTheme.colors.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 72.dp)
                .clearAndSetSemantics { contentDescription = "Overall ${scoreDescription(detail.scores.overall)}" },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(formatScore(detail.scores.overall), style = CinexTheme.textStyles.scoreLarge, color = CinexTheme.colors.text)
            Text("overall", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = CinexTheme.colors.textMuted)
        }
        VerticalDivider(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxHeight(),
            thickness = 1.dp,
            color = CinexTheme.colors.divider,
        )
        // Two columns; one with large text, so the category names aren't broken mid-word.
        val perRow = if (isLargeText()) 1 else 2
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CinemaCategory.entries.chunked(perRow).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { category ->
                        CategoryScore(category, detail, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryScore(category: CinemaCategory, detail: CinemaDetail, modifier: Modifier = Modifier) {
    val score = detail.scores[category]
    Column(
        modifier.clearAndSetSemantics { contentDescription = "${category.displayName} ${scoreDescription(score?.average)}" },
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(category.displayName, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = CinexTheme.colors.textMuted)
        Text(
            text = if (score == null) NO_SCORE_TEXT else "★ ${formatScore(score.average)}",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = CinexTheme.colors.text,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HallRow(
    hall: HallSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CinexTheme.colors.surface)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = "Rename or delete",
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    hall.hall.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = CinexTheme.colors.text,
                )
                Text(hallMeta(hall), style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
            }
            hall.favouriteSeat?.let { seat ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        CinexIcons.Star,
                        contentDescription = "Favourite seat",
                        tint = CinexTheme.colors.text,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(seat.displayText, style = MaterialTheme.typography.labelSmall, color = CinexTheme.colors.text)
                }
            }
            Text(
                text = formatScore(hall.overallScore),
                style = CinexTheme.textStyles.score.copy(textAlign = TextAlign.End),
                color = CinexTheme.colors.text,
                modifier = Modifier
                    .widthIn(min = 34.dp)
                    .clearAndSetSemantics { contentDescription = scoreDescription(hall.overallScore) },
            )
        }
        ItemMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            entries = hallMenuEntries(hall, onRename, onDelete),
        )
    }
}

/** Rename / delete entries of a hall; delete is disabled with an explanation for preloaded halls. */
internal fun hallMenuEntries(hall: HallSummary, onRename: () -> Unit, onDelete: () -> Unit): List<MenuEntry> = listOf(
    MenuEntry("Rename", onClick = onRename),
    if (hall.hall.isPreloaded) {
        MenuEntry("Delete", explanation = "Preloaded halls can't be deleted", enabled = false)
    } else {
        MenuEntry("Delete", onClick = onDelete)
    },
)
