package io.github.katznmeik.cinexperience.reminders

import android.content.BroadcastReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The reminders (spec §11.3): the [coordinator] that schedules them, the [notifier]
 * that posts them, the [intentToken] that the notifications' intents carry, and the
 * background work of the receivers in [scope] (the app's scope; one per process, from
 * `AppContainer`).
 */
class Reminders(
    val coordinator: ReminderCoordinator,
    val notifier: ReminderNotifier,
    val intentToken: ReminderIntentToken,
    private val scope: CoroutineScope,
) {
    /**
     * Runs [work] for a broadcast receiver in [scope], then finishes [pending]
     * (`goAsync()`; `null` only when a test calls the receiver directly). The work gets
     * [FOREGROUND_TIMEOUT] for a foreground broadcast ([foreground]: Android allows 10 s)
     * and [BACKGROUND_TIMEOUT] otherwise (a restart or an app update: 60 s). If it fails,
     * returns false (it couldn't plan) or runs out of time, a check-in alarm plans again
     * later ([ReminderCoordinator.scheduleRetry]). Nothing is thrown, not even an `Error`.
     */
    fun runForReceiver(pending: BroadcastReceiver.PendingResult?, foreground: Boolean, work: suspend () -> Boolean) {
        scope.launch {
            try {
                val planned = try {
                    withTimeoutOrNull(if (foreground) FOREGROUND_TIMEOUT else BACKGROUND_TIMEOUT) { work() } == true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Nothing is logged: no visit data in logs.
                    false
                }
                if (!planned) coordinator.scheduleRetry()
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        /** For a foreground broadcast (e.g. a reminder's alarm), within Android's 10 s. */
        val FOREGROUND_TIMEOUT: Duration = 8.seconds

        /** For a background broadcast (a restart, an app update), within Android's 60 s. */
        val BACKGROUND_TIMEOUT: Duration = 50.seconds
    }
}
