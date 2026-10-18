package io.github.katznmeik.cinexperience.reminders

import android.content.BroadcastReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The reminders (spec §11.3): the [coordinator] that schedules them, the [notifier]
 * that posts them, and the background work of the receivers in [scope] (the app's
 * scope; one per process, from `AppContainer`).
 */
class Reminders(
    val coordinator: ReminderCoordinator,
    val notifier: ReminderNotifier,
    private val scope: CoroutineScope,
) {
    /**
     * Runs [work] for a broadcast receiver in [scope], at most [RECEIVER_TIMEOUT] (well
     * within Android's limit for receivers), then finishes [pending] (`goAsync()`;
     * `null` only when a test calls the receiver directly). A failure or a timeout
     * posts and schedules nothing more; the next start or change schedules again.
     */
    fun runForReceiver(pending: BroadcastReceiver.PendingResult?, work: suspend () -> Unit) {
        scope.launch {
            try {
                withTimeout(RECEIVER_TIMEOUT) { work() }
            } catch (e: TimeoutCancellationException) {
                // Too slow (e.g. the database still opening): given up.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing is logged: no visit data in logs.
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        val RECEIVER_TIMEOUT: Duration = 8.seconds
    }
}
