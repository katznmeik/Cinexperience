package io.github.katznmeik.cinexperience.feature.visits.edit

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.data.files.VisitPhotoStore
import io.github.katznmeik.cinexperience.core.model.CinemaCategory
import io.github.katznmeik.cinexperience.core.model.CinemaRatings
import io.github.katznmeik.cinexperience.core.model.HallCategory
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.ui.component.ChoiceChip
import io.github.katznmeik.cinexperience.core.ui.component.FormMessage
import io.github.katznmeik.cinexperience.core.ui.component.MessageSnackbarHost
import io.github.katznmeik.cinexperience.core.ui.component.MutedText
import io.github.katznmeik.cinexperience.core.ui.component.RowDivider
import io.github.katznmeik.cinexperience.core.ui.component.RowGroup
import io.github.katznmeik.cinexperience.core.ui.component.SectionHeader
import io.github.katznmeik.cinexperience.core.ui.component.StarRatingInput
import io.github.katznmeik.cinexperience.core.ui.component.TopBarIconButton
import io.github.katznmeik.cinexperience.core.ui.component.rememberLoadedImage
import io.github.katznmeik.cinexperience.core.ui.component.rememberMessageSnackbarState
import io.github.katznmeik.cinexperience.core.ui.component.toPickerDate
import io.github.katznmeik.cinexperience.core.ui.component.toPickerMillis
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.navigation.CloseOnce
import io.github.katznmeik.cinexperience.core.ui.text.dateText
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.visits.chipText
import io.github.katznmeik.cinexperience.feature.visits.formLabel
import java.time.LocalDate
import java.time.LocalTime

/** Everything the form can do; the defaults do nothing (screenshots, previews). */
internal interface VisitEditActions {
    fun onClose() {}
    fun onSave() {}
    fun onSelectCinema(cinemaId: Long) {}
    fun onSelectHall(hallId: Long) {}
    fun onDate(date: LocalDate) {}

    /** A start time from the time picker, or `null` to clear it. */
    fun onStartTime(time: LocalTime?) {}
    fun onFilmTitle(text: String) {}
    fun onToggleFormat(format: ScreeningFormat) {}
    fun onOpenSeatPicker() {}
    fun onClearSeat() {}
    fun onRateSeat(value: Int) {}
    fun onRateHall(category: HallCategory, value: Int) {}
    fun onRateCinema(category: CinemaCategory, value: Int) {}
    fun onNote(text: String) {}
    fun onTakePhoto() {}
    fun onChoosePhoto() {}
    fun onRemovePhoto() {}
    fun onDelete() {}
    fun onConfirmDiscard() {}
    fun onConfirmDelete() {}
    fun onDismissDialog() {}
    fun onMessageShown() {}

    // Ticket section
    fun onShowTicket() {}
    fun onRemoveTicket() {}
    fun onConfirmRemoveTicket() {}

    // Seat picker
    fun onPickerTap(seatId: SeatId) {}
    fun onPickerChoose(seatId: SeatId) {}
    fun onPickerConfirm() {}
    fun onPickerClose() {}
}

private fun VisitEditViewModel.actions(choosePhoto: () -> Unit, showTicket: () -> Unit) = object : VisitEditActions {
    override fun onClose() = requestClose()
    override fun onSave() = save()
    override fun onSelectCinema(cinemaId: Long) = selectCinema(cinemaId)
    override fun onSelectHall(hallId: Long) = selectHall(hallId)
    override fun onDate(date: LocalDate) = setDate(date)
    override fun onStartTime(time: LocalTime?) = setStartTime(time)
    override fun onShowTicket() = showTicket()
    override fun onRemoveTicket() = requestRemoveTicket()
    override fun onConfirmRemoveTicket() = confirmRemoveTicket()
    override fun onFilmTitle(text: String) = setFilmTitle(text)
    override fun onToggleFormat(format: ScreeningFormat) = toggleFormat(format)
    override fun onOpenSeatPicker() = openSeatPicker()
    override fun onClearSeat() = clearSeat()
    override fun onRateSeat(value: Int) = rateSeat(value)
    override fun onRateHall(category: HallCategory, value: Int) = rateHall(category, value)
    override fun onRateCinema(category: CinemaCategory, value: Int) = rateCinema(category, value)
    override fun onNote(text: String) = setNote(text)
    override fun onTakePhoto() = requestCamera()
    override fun onChoosePhoto() = choosePhoto()
    override fun onRemovePhoto() = removePhoto()
    override fun onDelete() = requestDelete()
    override fun onConfirmDiscard() = discard()
    override fun onConfirmDelete() = confirmDelete()
    override fun onDismissDialog() = dismissDialog()
    override fun onMessageShown() = messageShown()
    override fun onPickerTap(seatId: SeatId) = tapPickerSeat(seatId)
    override fun onPickerChoose(seatId: SeatId) = choosePickerSeat(seatId)
    override fun onPickerConfirm() = confirmSeat()
    override fun onPickerClose() = closeSeatPicker()
}

/**
 * @param onShowTicket opens the ticket screen (spec §6.11) for the visit with this id
 *   (the Ticket section's "Show ticket"); the form stays below it, unsaved changes included.
 */
@Composable
internal fun VisitEditEntry(
    viewModel: VisitEditViewModel,
    photoStore: VisitPhotoStore,
    onClose: () -> Unit,
    onShowTicket: (visitId: Long) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.onGalleryPhotoPicked(uri?.toString())
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        viewModel.onCameraResult(taken)
    }
    val currentShowTicket by rememberUpdatedState(onShowTicket)
    val actions = remember(viewModel, gallery) {
        viewModel.actions(
            choosePhoto = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            showTicket = { viewModel.uiState.value.ticket?.visitId?.let { currentShowTicket(it) } },
        )
    }
    LaunchedEffect(state.cameraRequest) {
        val uri = state.cameraRequest ?: return@LaunchedEffect
        try {
            camera.launch(Uri.parse(uri))
            viewModel.onCameraLaunched()
        } catch (e: ActivityNotFoundException) {
            viewModel.onCameraUnavailable()
        }
    }
    val path = state.form.photoPath
    val photo by rememberLoadedImage(path) { photoStore.loadPhoto(it, THUMBNAIL_DECODE_SIZE) }
    CloseOnce(state.isClosed, onClose)
    // Stays enabled while saving: Back is then swallowed instead of leaving mid-save.
    BackHandler(enabled = !state.isClosed) { actions.onClose() }
    VisitEditScreen(state, photo, actions)
}

/** Shown with a saved seat that was since removed from the hall's layout. */
internal const val SEAT_MISSING_HINT =
    "Your saved seat is no longer in this hall's layout. It is kept unless you pick another seat or remove it."

/** Decoded size of the photo thumbnail (px); the tile is 96 dp. */
private const val THUMBNAIL_DECODE_SIZE = 512

/** The visit form (spec §6.4), stateless. Shows the seat picker instead while it is open. */
@Composable
internal fun VisitEditScreen(
    state: VisitEditUiState,
    photo: ImageBitmap?,
    actions: VisitEditActions,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = rememberMessageSnackbarState(state.message, actions::onMessageShown)
    Box(modifier.fillMaxSize()) {
        val picker = state.seatPicker
        if (picker != null) {
            SeatPickerScreen(state, picker, actions)
        } else {
            Column(Modifier.fillMaxSize()) {
                FormTopBar(
                    title = if (state.isEdit) "Edit visit" else "Add visit",
                    canSave = state.canSave,
                    actions = actions,
                )
                when (state.status) {
                    VisitEditStatus.Loading -> Unit
                    VisitEditStatus.NotFound -> FormMessage("This visit no longer exists.")
                    VisitEditStatus.LoadFailed -> FormMessage(VisitEditViewModel.LOAD_FAILED)
                    VisitEditStatus.Ready -> FormContent(state, photo, actions)
                }
            }
        }
        MessageSnackbarHost(snackbarHostState)
    }
    DialogHost(state.dialog, actions)
}

@Composable
private fun FormTopBar(title: String, canSave: Boolean, actions: VisitEditActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarIconButton(icon = CinexIcons.Close, contentDescription = "Close", onClick = actions::onClose)
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.titleLarge,
            color = CinexTheme.colors.text,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
                .semantics { heading() },
        )
        LightButton("Save", enabled = canSave, onClick = actions::onSave)
    }
}

/** The filled light button of the top bars ("Save", "Use seat"). */
@Composable
private fun LightButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CinexTheme.colors.text,
            contentColor = CinexTheme.colors.onPrimary,
            disabledContainerColor = CinexTheme.colors.surfaceRaised,
            disabledContentColor = CinexTheme.colors.textMuted,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun FormContent(state: VisitEditUiState, photo: ImageBitmap?, actions: VisitEditActions) {
    val form = state.form
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var cinemaExpanded by rememberSaveable { mutableStateOf(form.cinemaRatings != CinemaRatings.NONE) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 1–2: cinema, hall, date, start time, film
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldPair(
                first = { fieldModifier ->
                    LabelledField("Cinema", fieldModifier) {
                        DropdownField(
                            text = state.cinemaName,
                            placeholder = "Choose",
                            contentDescription = "Cinema",
                            options = state.cinemas.map { it.id to it.name },
                            onSelect = actions::onSelectCinema,
                        )
                    }
                },
                second = { fieldModifier ->
                    LabelledField("Hall", fieldModifier) {
                        DropdownField(
                            text = state.hallName,
                            placeholder = if (state.cinemaName == null) "Choose cinema first" else "Choose",
                            contentDescription = "Hall",
                            options = state.halls.map { it.id to it.name },
                            onSelect = actions::onSelectHall,
                        )
                    }
                },
            )
            FieldPair(
                first = { fieldModifier ->
                    LabelledField("Date", fieldModifier) {
                        FieldBox(
                            onClick = { showDatePicker = true },
                            contentDescription = "Date, ${dateText(form.date)}",
                        ) {
                            FieldText(dateText(form.date), Modifier.weight(1f))
                        }
                    }
                },
                second = { fieldModifier ->
                    LabelledField("Start time", fieldModifier) {
                        StartTimeField(
                            time = form.startTime,
                            onOpen = { showTimePicker = true },
                            onClear = { actions.onStartTime(null) },
                        )
                    }
                },
            )
            LabelledField("Film") {
                TextInput(
                    value = form.filmTitle,
                    onValueChange = actions::onFilmTitle,
                    placeholder = "Film title",
                    contentDescription = "Film title",
                    singleLine = true,
                )
            }
        }

        LabelledField("Format") {
            FlowRow(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScreeningFormat.entries.forEach { format ->
                    ChoiceChip(
                        text = format.chipText,
                        selected = form.format == format,
                        onClick = { actions.onToggleFormat(format) },
                    )
                }
            }
        }

        // 3–4: seat (and the ticket, if the visit has one) and seat rating
        SeatCard(state, actions)
        state.ticket?.let { TicketSection(it, removing = state.isRemovingTicket, enabled = !state.isSaving, actions = actions) }

        // 5: hall ratings
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Hall · all optional")
            RowGroup {
                HallCategory.entries.forEachIndexed { index, category ->
                    if (index > 0) RowDivider()
                    StarRow(category.formLabel, form.hallRatings[category]?.value) { actions.onRateHall(category, it) }
                }
            }
        }

        // 6: cinema ratings (collapsible)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CollapsibleHeader(
                text = "Cinema · ${state.cinemaName ?: "not chosen"}",
                expanded = cinemaExpanded,
                onToggle = { cinemaExpanded = !cinemaExpanded },
            )
            if (cinemaExpanded) {
                RowGroup {
                    CinemaCategory.entries.forEachIndexed { index, category ->
                        if (index > 0) RowDivider()
                        StarRow(category.displayName, form.cinemaRatings[category]?.value) { actions.onRateCinema(category, it) }
                    }
                }
            }
        }

        // 7: note and photo
        LabelledField("Note") {
            TextInput(
                value = form.note,
                onValueChange = actions::onNote,
                placeholder = "How was it?",
                contentDescription = "Note",
                singleLine = false,
                minHeight = 96.dp,
            )
        }
        LabelledField("Photo") {
            PhotoField(photo = photo, hasPhoto = form.photoPath != null, busy = state.isPhotoBusy, actions = actions)
        }

        if (state.isEdit) {
            TextButton(
                onClick = actions::onDelete,
                enabled = !state.isSaving,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Icon(CinexIcons.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = CinexTheme.colors.text)
                Spacer(Modifier.size(8.dp))
                Text("Delete visit", style = MaterialTheme.typography.labelLarge, color = CinexTheme.colors.text)
            }
        }
    }
    if (showDatePicker) {
        VisitDatePicker(
            date = form.date,
            onDate = {
                showDatePicker = false
                actions.onDate(it)
            },
            onDismiss = { showDatePicker = false },
        )
    }
    if (showTimePicker) {
        VisitTimePicker(
            time = form.startTime,
            onTime = {
                showTimePicker = false
                actions.onStartTime(it)
            },
            onClear = {
                showTimePicker = false
                actions.onStartTime(null)
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

@Composable
private fun SeatCard(state: VisitEditUiState, actions: VisitEditActions) {
    val form = state.form
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CinexTheme.colors.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your seat", style = MaterialTheme.typography.labelMedium, color = CinexTheme.colors.textMuted)
                val (title, muted) = when {
                    state.seatLabel != null -> state.seatLabel.shortText to false
                    state.seatMissing && state.layoutUnreadable -> "Seat can't be shown" to true
                    state.seatMissing -> "Seat no longer in layout" to true
                    else -> "No seat chosen" to true
                }
                Text(
                    text = title.uppercase(),
                    style = CinexTheme.textStyles.cardTitle.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize),
                    color = if (muted) CinexTheme.colors.textMuted else CinexTheme.colors.text,
                    // "Row 7, Seat 10" instead of "R7 · S10"; other titles are read as shown.
                    modifier = state.seatLabel?.let { label -> Modifier.semantics { contentDescription = label.description } } ?: Modifier,
                )
            }
            val canPick = state.seatPickerUnavailable == null
            val pickColor = if (canPick) CinexTheme.colors.text else CinexTheme.colors.textMuted
            OutlinedButton(
                onClick = actions::onOpenSeatPicker,
                enabled = canPick,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, if (canPick) CinexTheme.colors.outline else CinexTheme.colors.divider),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.minimumInteractiveComponentSize().heightIn(min = 40.dp),
            ) {
                Icon(CinexIcons.Grid, contentDescription = null, modifier = Modifier.size(16.dp), tint = pickColor)
                Spacer(Modifier.size(6.dp))
                Text("Pick on map", style = MaterialTheme.typography.labelMedium, color = pickColor)
            }
        }
        state.seatPickerUnavailable?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
        }
        if (state.seatMissing && !state.layoutUnreadable) {
            Text(SEAT_MISSING_HINT, style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
        }
        if (form.seatId != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = actions::onClearSeat)
                    .padding(end = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("Remove seat", style = MaterialTheme.typography.labelMedium, color = CinexTheme.colors.textMuted)
            }
        }
        RowDivider()
        Text("How was this seat?", style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text)
        StarRatingInput(
            rating = form.seatRating?.value,
            onRate = actions::onRateSeat,
            label = "Seat rating",
            starSize = 34.dp,
            touchSize = 52.dp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/**
 * Material date picker in a dialog, picker mode only (no text input). The
 * picker works in UTC midnight millis; the visit date is a plain [LocalDate].
 */
@Composable
private fun VisitDatePicker(date: LocalDate, onDate: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.toPickerMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { pickerState.selectedDateMillis?.let { onDate(it.toPickerDate()) } ?: onDismiss() }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState, showModeToggle = false)
    }
}

@Composable
private fun DialogHost(dialog: VisitEditDialog?, actions: VisitEditActions) {
    when (dialog) {
        null -> Unit
        VisitEditDialog.ConfirmDiscard -> AlertDialog(
            onDismissRequest = actions::onDismissDialog,
            title = { Text("Discard this visit?") },
            text = { MutedText("Your changes will be lost.") },
            confirmButton = { TextButton(onClick = actions::onConfirmDiscard) { Text("Discard") } },
            dismissButton = { TextButton(onClick = actions::onDismissDialog) { Text("Keep editing") } },
        )
        VisitEditDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = actions::onDismissDialog,
            title = { Text("Delete this visit?") },
            text = { MutedText("The visit, its ratings and its photo are deleted. This can't be undone.") },
            confirmButton = { TextButton(onClick = actions::onConfirmDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = actions::onDismissDialog) { Text("Cancel") } },
        )
        VisitEditDialog.ConfirmRemoveTicket -> AlertDialog(
            onDismissRequest = actions::onDismissDialog,
            title = { Text("Remove the ticket?") },
            text = { MutedText(REMOVE_TICKET_TEXT) },
            confirmButton = { TextButton(onClick = actions::onConfirmRemoveTicket) { Text("Remove") } },
            dismissButton = { TextButton(onClick = actions::onDismissDialog) { Text("Cancel") } },
        )
    }
}
