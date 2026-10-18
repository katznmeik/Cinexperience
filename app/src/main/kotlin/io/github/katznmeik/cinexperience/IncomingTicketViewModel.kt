package io.github.katznmeik.cinexperience

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.github.katznmeik.cinexperience.feature.tickets.ImportTicketRoute
import io.github.katznmeik.cinexperience.feature.tickets.importing.TicketIntents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A shared or opened ticket PDF (spec §6.10) waiting to be imported: [MainActivity]
 * offers each intent once ([offer]), `CinexApp` opens the import when it may (not
 * while a backup import blocks every write) and then [consume]s it. Kept in the
 * activity's saved state (only the file's address, never its content), so a ticket
 * waiting for a backup import survives rotation and process death and is still
 * imported exactly once.
 */
class IncomingTicketViewModel(private val savedState: SavedStateHandle) : ViewModel() {

    private val _pending = MutableStateFlow(restored())

    /** The import to open, `null` if none is waiting. */
    val pending: StateFlow<ImportTicketRoute?> = _pending.asStateFlow()

    /** Takes the ticket [intent] asks to import, if any ([TicketIntents.importRoute]); a newer one replaces a waiting one. */
    fun offer(intent: Intent?, ownPackage: String) {
        val route = TicketIntents.importRoute(intent, ownPackage) ?: return
        savedState[PENDING_KEY] = true
        savedState[URI_KEY] = route.uri
        _pending.value = route
    }

    /** The import was opened. */
    fun consume() {
        savedState[PENDING_KEY] = false
        savedState[URI_KEY] = null
        _pending.value = null
    }

    private fun restored(): ImportTicketRoute? =
        if (savedState.get<Boolean>(PENDING_KEY) == true) ImportTicketRoute(savedState.get<String>(URI_KEY)) else null

    private companion object {
        const val PENDING_KEY = "incomingTicket.pending"
        const val URI_KEY = "incomingTicket.uri"
    }
}
