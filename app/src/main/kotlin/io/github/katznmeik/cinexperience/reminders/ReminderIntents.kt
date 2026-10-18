package io.github.katznmeik.cinexperience.reminders

import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.github.katznmeik.cinexperience.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a reminder opens (spec §11.3 rule 7): the visit's form, or with [showTicket] its ticket. */
data class ReminderTarget(val visitId: Long, val showTicket: Boolean)

/**
 * The intents of a reminder's notification: explicit intents to [MainActivity] with
 * an app-specific action and the visit id. Tapping the notification opens the visit
 * (`EditVisitRoute`), "Show ticket" its ticket (`TicketRoute`). They only open a
 * screen, never write: an unknown or deleted visit shows that screen's message.
 * [MainActivity] is exported, so another app could send them too; that opens a screen
 * of this app, nothing more.
 */
object ReminderIntents {
    const val ACTION_OPEN_VISIT: String = "io.github.katznmeik.cinexperience.action.OPEN_VISIT"
    const val ACTION_SHOW_TICKET: String = "io.github.katznmeik.cinexperience.action.SHOW_TICKET"
    const val EXTRA_VISIT_ID: String = "io.github.katznmeik.cinexperience.extra.VISIT_ID"

    fun openVisit(context: Context, visitId: Long): Intent = intent(context, ACTION_OPEN_VISIT, visitId)

    fun showTicket(context: Context, visitId: Long): Intent = intent(context, ACTION_SHOW_TICKET, visitId)

    /**
     * In the app's own task: an activity already showing gets the intent (`onNewIntent`)
     * and opens the screen on top of what it shows. The identifier keeps the pending
     * intents of different visits apart.
     */
    private fun intent(context: Context, action: String, visitId: Long): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(action)
            .setIdentifier("visit-$visitId")
            .putExtra(EXTRA_VISIT_ID, visitId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * What [intent] asks to open; `null` if it is no reminder intent, has no valid visit
     * id, or was launched again from the recent apps
     * ([Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY]: an intent is handled once).
     */
    fun targetOf(intent: Intent?): ReminderTarget? {
        intent ?: return null
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val showTicket = when (intent.action) {
            ACTION_OPEN_VISIT -> false
            ACTION_SHOW_TICKET -> true
            else -> return null
        }
        val visitId = try {
            intent.getLongExtra(EXTRA_VISIT_ID, 0L)
        } catch (e: RuntimeException) {
            // A malformed extra from another app (e.g. BadParcelableException).
            0L
        }
        return if (visitId > 0) ReminderTarget(visitId, showTicket) else null
    }
}

/**
 * A reminder's screen waiting to be opened, like [io.github.katznmeik.cinexperience.IncomingTicketViewModel]:
 * `MainActivity` offers each intent once ([offer]), `CinexApp` opens the screen when
 * it may (not while a backup import blocks every write) and then [consume]s it. Kept in
 * the activity's saved state, so it is opened exactly once, also across rotation and
 * process death.
 */
class IncomingReminderViewModel(private val savedState: SavedStateHandle) : ViewModel() {

    private val _pending = MutableStateFlow(restored())

    /** The screen to open, `null` if none is waiting. */
    val pending: StateFlow<ReminderTarget?> = _pending.asStateFlow()

    /** Takes what [intent] asks to open ([ReminderIntents.targetOf]); a newer one replaces a waiting one. Returns it. */
    fun offer(intent: Intent?): ReminderTarget? {
        val target = ReminderIntents.targetOf(intent) ?: return null
        savedState[VISIT_KEY] = target.visitId
        savedState[TICKET_KEY] = target.showTicket
        _pending.value = target
        return target
    }

    /** The screen was opened. */
    fun consume() {
        savedState[VISIT_KEY] = null
        savedState[TICKET_KEY] = null
        _pending.value = null
    }

    private fun restored(): ReminderTarget? {
        val visitId = savedState.get<Long>(VISIT_KEY)?.takeIf { it > 0 } ?: return null
        return ReminderTarget(visitId, savedState.get<Boolean>(TICKET_KEY) == true)
    }

    private companion object {
        const val VISIT_KEY = "incomingReminder.visitId"
        const val TICKET_KEY = "incomingReminder.showTicket"
    }
}
