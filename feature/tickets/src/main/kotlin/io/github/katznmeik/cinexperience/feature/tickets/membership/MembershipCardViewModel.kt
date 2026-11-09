package io.github.katznmeik.cinexperience.feature.tickets.membership

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.katznmeik.cinexperience.core.data.settings.MembershipCardStore
import io.github.katznmeik.cinexperience.core.model.MembershipCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the membership card screen shows (spec §6.12). */
sealed interface MembershipCardContent {
    data object Loading : MembershipCardContent

    /** No card yet: the explanation and "Add membership card". */
    data object None : MembershipCardContent

    data class Shown(val card: MembershipCard) : MembershipCardContent

    data class Error(val message: String) : MembershipCardContent
}

/**
 * @property confirmRemove the "Remove card" confirmation is open.
 * @property message a one-off message (e.g. the removal failed), shown once.
 */
data class MembershipCardUiState(
    val content: MembershipCardContent = MembershipCardContent.Loading,
    val confirmRemove: Boolean = false,
    val isRemoving: Boolean = false,
    val message: String? = null,
)

/**
 * The membership card (spec §6.12): shows the stored card and removes it after a
 * confirmation. It works offline and never expires.
 */
class MembershipCardViewModel(private val store: MembershipCardStore) : ViewModel() {

    private data class Local(val confirmRemove: Boolean = false, val isRemoving: Boolean = false, val message: String? = null)

    private val local = MutableStateFlow(Local())

    private val content = store.card
        .map { card -> if (card == null) MembershipCardContent.None else MembershipCardContent.Shown(card) }
        .catch { emit(MembershipCardContent.Error(LOAD_FAILED)) }

    val uiState: StateFlow<MembershipCardUiState> = combine(content, local) { content, local ->
        MembershipCardUiState(content, local.confirmRemove && content is MembershipCardContent.Shown, local.isRemoving, local.message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MembershipCardUiState())

    fun onRemove() = local.update { if (it.isRemoving) it else it.copy(confirmRemove = true) }

    fun onCancelRemove() = local.update { it.copy(confirmRemove = false) }

    fun onConfirmRemove() {
        if (local.value.isRemoving) return
        local.update { it.copy(confirmRemove = false, isRemoving = true) }
        viewModelScope.launch {
            val removed = try {
                store.remove()
            } catch (e: Exception) {
                false
            }
            local.update { it.copy(isRemoving = false, message = if (removed) REMOVED else REMOVE_FAILED) }
        }
    }

    fun onMessageShown() = local.update { it.copy(message = null) }

    companion object {
        const val LOAD_FAILED = "Couldn't load the membership card."
        const val REMOVE_FAILED = "Couldn't remove the membership card. Please try again."
        const val REMOVED = "Membership card removed."

        fun factory(store: MembershipCardStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { MembershipCardViewModel(store) }
        }
    }
}
