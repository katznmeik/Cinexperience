package io.github.katznmeik.cinexperience.feature.visits.edit

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.data.files.VisitPhotoStore
import io.github.katznmeik.cinexperience.core.model.CinemaCategory
import io.github.katznmeik.cinexperience.core.model.CinemaRatings
import io.github.katznmeik.cinexperience.core.model.HallCategory
import io.github.katznmeik.cinexperience.core.model.ScreeningFormat
import io.github.katznmeik.cinexperience.core.model.SeatId
import io.github.katznmeik.cinexperience.core.ui.component.ChoiceChip
import io.github.katznmeik.cinexperience.core.ui.component.RowDivider
import io.github.katznmeik.cinexperience.core.ui.component.RowGroup
import io.github.katznmeik.cinexperience.core.ui.component.SectionHeader
import io.github.katznmeik.cinexperience.core.ui.component.StarRatingInput
import io.github.katznmeik.cinexperience.core.ui.component.TopBarIconButton
import io.github.katznmeik.cinexperience.core.ui.component.isLargeText
import io.github.katznmeik.cinexperience.core.ui.component.toPickerDate
import io.github.katznmeik.cinexperience.core.ui.component.toPickerMillis
import io.github.katznmeik.cinexperience.core.ui.icon.CinexIcons
import io.github.katznmeik.cinexperience.core.ui.text.dateText
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.feature.visits.chipText
import io.github.katznmeik.cinexperience.feature.visits.formLabel
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/** Everything the form can do; the defaults do nothing (screenshots, previews). */
internal interface VisitEditActions {
    fun onClose() {}
    fun onSave() {}
    fun onSelectCinema(cinemaId: Long) {}
    fun onSelectHall(hallId: Long) {}
    fun onDate(date: LocalDate) {}
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

    // Seat picker
    fun onPickerTap(seatId: SeatId) {}
    fun onPickerChoose(seatId: SeatId) {}
    fun onPickerConfirm() {}
    fun onPickerClose() {}
}

private fun VisitEditViewModel.actions(choosePhoto: () -> Unit) = object : VisitEditActions {
    override fun onClose() = requestClose()
    override fun onSave() = save()
    override fun onSelectCinema(cinemaId: Long) = selectCinema(cinemaId)
    override fun onSelectHall(hallId: Long) = selectHall(hallId)
    override fun onDate(date: LocalDate) = setDate(date)
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

@Composable
internal fun VisitEditEntry(
    viewModel: VisitEditViewModel,
    photoStore: VisitPhotoStore,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.onGalleryPhotoPicked(uri?.toString())
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        viewModel.onCameraResult(taken)
    }
    val actions = remember(viewModel, gallery) {
        viewModel.actions(choosePhoto = {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
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
    val photo by produceState<ImageBitmap?>(null, path) {
        value = path?.let {
            try {
                photoStore.loadPhoto(it, THUMBNAIL_DECODE_SIZE)?.asImageBitmap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    // Close exactly once, also if the screen is recreated while it leaves.
    var closeHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.isClosed) {
        if (state.isClosed && !closeHandled) {
            closeHandled = true
            onClose()
        }
    }
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
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            actions.onMessageShown()
        }
    }
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
                    VisitEditStatus.NotFound -> Message("This visit no longer exists.")
                    VisitEditStatus.LoadFailed -> Message("Couldn't load the visit.")
                    VisitEditStatus.Ready -> FormContent(state, photo, actions)
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
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
    var cinemaExpanded by rememberSaveable { mutableStateOf(form.cinemaRatings != CinemaRatings.NONE) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 1–2: cinema, hall, date, film
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
                    LabelledField("Film", fieldModifier) {
                        TextInput(
                            value = form.filmTitle,
                            onValueChange = actions::onFilmTitle,
                            placeholder = "Film title",
                            contentDescription = "Film title",
                            singleLine = true,
                        )
                    }
                },
            )
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

        // 3–4: seat and seat rating
        SeatCard(state, actions)

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
}

/**
 * Two fields side by side; with large text one below the other, so their
 * texts ("Choose cinema first", a film title) aren't cut off.
 */
@Composable
private fun FieldPair(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    if (isLargeText()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            first(Modifier.fillMaxWidth())
            second(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    }
}

@Composable
private fun LabelledField(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = CinexTheme.colors.textMuted)
        content()
    }
}

/** The 48 dp surface box of the form's fields. */
@Composable
private fun FieldBox(
    onClick: (() -> Unit)?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(FieldShape)
            .background(CinexTheme.colors.surface)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .then(
                // The description replaces the visible texts (e.g. "Hall, not chosen" instead of "Choose").
                if (contentDescription != null) {
                    Modifier.clearAndSetSemantics {
                        this.contentDescription = contentDescription
                        if (onClick != null) {
                            role = Role.Button
                            onClick {
                                onClick()
                                true
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private val FieldShape = RoundedCornerShape(12.dp)

@Composable
private fun FieldText(text: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (muted) FontWeight.Normal else FontWeight.Medium),
        color = if (muted) CinexTheme.colors.textMuted else CinexTheme.colors.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A dropdown-like field ("delphi LUX ▾") opening a menu of [options] (id to name). */
@Composable
private fun DropdownField(
    text: String?,
    placeholder: String,
    contentDescription: String,
    options: List<Pair<Long, String>>,
    onSelect: (Long) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FieldBox(
            onClick = if (options.isEmpty()) null else ({ open = true }),
            contentDescription = when {
                text != null -> "$contentDescription, $text"
                options.isEmpty() -> "$contentDescription, not chosen, ${placeholder.lowercase()}"
                else -> "$contentDescription, not chosen"
            },
        ) {
            FieldText(text ?: placeholder, Modifier.weight(1f), muted = text == null)
            Icon(
                CinexIcons.ChevronDown,
                contentDescription = null,
                tint = if (options.isEmpty()) CinexTheme.colors.outline else CinexTheme.colors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = CinexTheme.colors.surfaceRaised,
        ) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text) },
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

/** A plain text field in the form's field style, with a muted [placeholder]. */
@Composable
private fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    contentDescription: String,
    singleLine: Boolean,
    minHeight: Dp = 48.dp,
) {
    val style = MaterialTheme.typography.bodyMedium.copy(color = CinexTheme.colors.text, fontWeight = FontWeight.Medium)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = style,
        cursorBrush = SolidColor(CinexTheme.colors.text),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = contentDescription },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .clip(FieldShape)
                    .background(CinexTheme.colors.surface)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty()) {
                    // Not read out: the field's description already names it.
                    Text(
                        placeholder,
                        style = style.copy(fontWeight = FontWeight.Normal),
                        color = CinexTheme.colors.textMuted,
                        maxLines = 1,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                inner()
            }
        },
    )
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

/** One rating row: label on the left, five stars on the right, at least 52 dp tall. */
@Composable
private fun StarRow(label: String, rating: Int?, onRate: (Int) -> Unit) {
    // With large text the label goes above the stars, so it isn't broken mid-word.
    if (isLargeText()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text)
            StarRatingInput(rating = rating, onRate = onRate, label = label, modifier = Modifier.align(Alignment.End))
        }
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text, modifier = Modifier.weight(1f))
        StarRatingInput(rating = rating, onRate = onRate, label = label)
    }
}

@Composable
private fun CollapsibleHeader(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionHeader(text, Modifier.weight(1f))
        Icon(
            if (expanded) CinexIcons.ChevronDown else CinexIcons.ChevronRight,
            contentDescription = null,
            tint = CinexTheme.colors.textMuted,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(18.dp),
        )
    }
}

@Composable
private fun PhotoField(photo: ImageBitmap?, hasPhoto: Boolean, busy: Boolean, actions: VisitEditActions) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box {
            val tileShape = RoundedCornerShape(14.dp)
            if (hasPhoto) {
                // A picture without text: a fixed size is fine.
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(tileShape)
                        .background(CinexTheme.colors.surface)
                        .semantics { contentDescription = "Visit photo" },
                    contentAlignment = Alignment.Center,
                ) {
                    if (photo != null) {
                        Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(CinexIcons.Image, contentDescription = null, tint = CinexTheme.colors.textMuted)
                    }
                }
            } else {
                // At least 96 dp; wider and taller when "Add photo" needs it (large text).
                DashedTile(
                    modifier = Modifier
                        .sizeIn(minWidth = 96.dp, minHeight = 96.dp)
                        .clip(tileShape)
                        .clickable(enabled = !busy, role = Role.Button) { menuOpen = true },
                )
            }
            PhotoSourceMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onCamera = {
                    menuOpen = false
                    actions.onTakePhoto()
                },
                onGallery = {
                    menuOpen = false
                    actions.onChoosePhoto()
                },
            )
        }
        if (hasPhoto) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PhotoButton("Replace", "Replace photo", enabled = !busy, onClick = { menuOpen = true })
                PhotoButton("Remove", "Remove photo", enabled = !busy, onClick = actions::onRemovePhoto)
            }
        } else if (busy) {
            Text("Adding photo…", style = MaterialTheme.typography.bodySmall, color = CinexTheme.colors.textMuted)
        }
    }
}

/** "Replace" / "Remove" next to the photo, read out with their object ("Remove photo"). */
@Composable
private fun PhotoButton(text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = CinexTheme.colors.text,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun DashedTile(modifier: Modifier) {
    val outline = CinexTheme.colors.outline
    Column(
        modifier = modifier
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                )
            }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(CinexIcons.Camera, contentDescription = null, tint = CinexTheme.colors.textMuted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.size(6.dp))
        Text("Add photo", style = MaterialTheme.typography.labelSmall, color = CinexTheme.colors.textMuted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PhotoSourceMenu(expanded: Boolean, onDismiss: () -> Unit, onCamera: () -> Unit, onGallery: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = CinexTheme.colors.surfaceRaised) {
        DropdownMenuItem(
            text = { Text("Camera", style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text) },
            leadingIcon = { Icon(CinexIcons.Camera, contentDescription = null, tint = CinexTheme.colors.text) },
            onClick = onCamera,
        )
        DropdownMenuItem(
            text = { Text("Gallery", style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.text) },
            leadingIcon = { Icon(CinexIcons.Image, contentDescription = null, tint = CinexTheme.colors.text) },
            onClick = onGallery,
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
            text = { DialogText("Your changes will be lost.") },
            confirmButton = { TextButton(onClick = actions::onConfirmDiscard) { Text("Discard") } },
            dismissButton = { TextButton(onClick = actions::onDismissDialog) { Text("Keep editing") } },
        )
        VisitEditDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = actions::onDismissDialog,
            title = { Text("Delete this visit?") },
            text = { DialogText("The visit, its ratings and its photo are deleted. This can't be undone.") },
            confirmButton = { TextButton(onClick = actions::onConfirmDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = actions::onDismissDialog) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DialogText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = CinexTheme.colors.textMuted)
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = CinexTheme.colors.textMuted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}
