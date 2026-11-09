package io.github.katznmeik.cinexperience.feature.tickets.membership

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.settings.MembershipCardStore
import io.github.katznmeik.cinexperience.core.model.MembershipCard
import io.github.katznmeik.cinexperience.core.model.QrModules
import io.github.katznmeik.cinexperience.core.ticket.card.CardImageError
import io.github.katznmeik.cinexperience.core.ticket.card.MembershipCardImageLimits
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketFileBytes
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketFileSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why a screenshot gave no QR code; [message] is user-facing. The form keeps what it had. */
enum class CardImageMessage(val message: String) {
    OPEN_FAILED("Couldn't open the image. Please choose the screenshot again."),
    TOO_LARGE("This image is too large. Please choose a screenshot of the card."),
    NOT_AN_IMAGE("This file isn't an image the app can read."),
    NO_QR_CODE("No QR code found. Choose a screenshot that shows the card's whole QR code."),
    SEVERAL_QR_CODES("This image shows more than one QR code. Choose a screenshot with only the card's code."),
    ;

    companion object {
        fun of(error: CardImageError): CardImageMessage = when (error) {
            CardImageError.TOO_LARGE -> TOO_LARGE
            CardImageError.NOT_AN_IMAGE -> NOT_AN_IMAGE
            CardImageError.NO_QR_CODE -> NO_QR_CODE
            CardImageError.SEVERAL_QR_CODES -> SEVERAL_QR_CODES
        }
    }
}

/**
 * The add / edit form of the membership card (spec §6.13).
 *
 * @property isLoading the stored card is still being read into the form.
 * @property isNew no card is stored yet ("Add membership card").
 * @property qrModules the QR code the card will have: the stored one, or the one read
 *   from the screenshot chosen now.
 * @property isReading a screenshot is being read.
 * @property isSaved the card was stored: the screen closes.
 */
data class EditMembershipCardUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    val qrModules: QrModules? = null,
    val name: String = "",
    val memberNumber: String = "",
    val membershipType: String = "",
    val isReading: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null,
    val isSaved: Boolean = false,
) {
    val canSave: Boolean get() = qrModules != null && !isLoading && !isReading && !isSaving && !isSaved
}

/**
 * Adds or edits the membership card (spec §6.13): the QR code comes from a screenshot
 * ([CardImageReader]), the texts are typed (capped at the card's limits). Save stores
 * the card in place of the previous one; Back discards. The form survives process
 * death in [savedState] (the QR code as [QrModules.encode] text). The screenshot is
 * read in memory only and never kept.
 *
 * @param fileSource opens the picked image (a `content:` URI of the photo picker).
 */
class EditMembershipCardViewModel(
    private val store: MembershipCardStore,
    private val fileSource: TicketFileSource,
    private val reader: CardImageReader,
    private val savedState: SavedStateHandle,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val readDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val state = MutableStateFlow(EditMembershipCardUiState())
    val uiState: StateFlow<EditMembershipCardUiState> = state.asStateFlow()

    private var reading: Job? = null

    init {
        if (savedState.get<Boolean>(KEY_LOADED) == true) {
            state.value = EditMembershipCardUiState(
                isLoading = false,
                isNew = savedState.get<Boolean>(KEY_IS_NEW) ?: true,
                qrModules = savedState.get<String>(KEY_QR)?.let(QrModules::decodeOrNull),
                name = savedState.get<String>(KEY_NAME).orEmpty(),
                memberNumber = savedState.get<String>(KEY_NUMBER).orEmpty(),
                membershipType = savedState.get<String>(KEY_TYPE).orEmpty(),
            )
        } else {
            viewModelScope.launch {
                val card = try {
                    store.card.first()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                state.update {
                    EditMembershipCardUiState(
                        isLoading = false,
                        isNew = card == null,
                        qrModules = card?.qrModules,
                        name = card?.name.orEmpty(),
                        memberNumber = card?.memberNumber.orEmpty(),
                        membershipType = if (card == null) DEFAULT_MEMBERSHIP_TYPE else card.membershipType.orEmpty(),
                    )
                }
                save()
            }
        }
    }

    fun onName(text: String) = edit { it.copy(name = capped(text, MembershipCard.MAX_NAME_LENGTH)) }

    fun onMemberNumber(text: String) = edit { it.copy(memberNumber = capped(text, MembershipCard.MAX_MEMBER_NUMBER_LENGTH)) }

    fun onMembershipType(text: String) = edit { it.copy(membershipType = capped(text, MembershipCard.MAX_MEMBERSHIP_TYPE_LENGTH)) }

    /** The photo picker's result; `null` (cancelled) changes nothing. A new pick replaces one being read. */
    fun onImageChosen(uri: String?) {
        if (uri == null || state.value.isLoading || state.value.isSaving || state.value.isSaved) return
        reading?.cancel()
        state.update { it.copy(isReading = true, message = null) }
        reading = viewModelScope.launch {
            val result = readImage(uri)
            state.update {
                when (result) {
                    is CardImageReadResult.Read -> it.copy(isReading = false, qrModules = result.qrModules)
                    is CardImageReadResult.Refused -> it.copy(isReading = false, message = CardImageMessage.of(result.error).message)
                    null -> it.copy(isReading = false, message = CardImageMessage.OPEN_FAILED.message)
                }
            }
            save()
        }
    }

    /** The image's QR code; `null` if the file couldn't be opened or read. */
    private suspend fun readImage(uri: String): CardImageReadResult? {
        val bytes = try {
            withContext(ioDispatcher) {
                fileSource.open(uri).use { TicketFileBytes.readAtMost(it, MembershipCardImageLimits.MAX_FILE_BYTES) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } ?: return CardImageReadResult.Refused(CardImageError.TOO_LARGE)
        return withContext(readDispatcher) { reader.read(bytes) }
    }

    fun onSave() {
        val current = state.value
        val qr = current.qrModules
        if (!current.canSave || qr == null) return
        state.update { it.copy(isSaving = true, message = null) }
        viewModelScope.launch {
            val card = MembershipCard.of(current.name, current.memberNumber, current.membershipType, qr)
            val saved = try {
                store.save(card)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            state.update { if (saved) it.copy(isSaving = false, isSaved = true) else it.copy(isSaving = false, message = SAVE_FAILED) }
        }
    }

    fun onMessageShown() = state.update { it.copy(message = null) }

    private fun edit(change: (EditMembershipCardUiState) -> EditMembershipCardUiState) {
        if (state.value.isLoading) return
        state.update(change)
        save()
    }

    /** The form into [savedState], for process death. */
    private fun save() {
        val current = state.value
        if (current.isLoading) return
        savedState[KEY_LOADED] = true
        savedState[KEY_IS_NEW] = current.isNew
        savedState[KEY_QR] = current.qrModules?.encode()
        savedState[KEY_NAME] = current.name
        savedState[KEY_NUMBER] = current.memberNumber
        savedState[KEY_TYPE] = current.membershipType
    }

    companion object {
        /** Pre-filled for a new card (spec §6.13). */
        const val DEFAULT_MEMBERSHIP_TYPE = "Yorck Unlimited"
        const val SAVE_FAILED = "Couldn't save the membership card. Please try again."

        private const val KEY_LOADED = "membershipCard.loaded"
        private const val KEY_IS_NEW = "membershipCard.isNew"
        private const val KEY_QR = "membershipCard.qr"
        private const val KEY_NAME = "membershipCard.name"
        private const val KEY_NUMBER = "membershipCard.number"
        private const val KEY_TYPE = "membershipCard.type"

        /** [text] cut to [max] code points, so the field never holds more than the card allows. */
        internal fun capped(text: String, max: Int): String =
            if (text.codePointCount(0, text.length) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))

        fun factory(store: MembershipCardStore, fileSource: TicketFileSource, reader: CardImageReader): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { EditMembershipCardViewModel(store, fileSource, reader, createSavedStateHandle()) }
            }
    }
}
