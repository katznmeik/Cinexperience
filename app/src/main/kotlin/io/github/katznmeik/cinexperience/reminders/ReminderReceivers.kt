package io.github.katznmeik.cinexperience.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.katznmeik.cinexperience.CinexApplication

/**
 * A reminder alarm fired (spec §11.3): a reminder's own alarm is checked again and its
 * notification posted ([ReminderNotifier.onAlarm]); a check-in only plans. Either way the
 * reminders are planned again, so the chain of alarms goes on ([ReminderAlarmChain]) and
 * the reminders beyond the plan's limit follow. Not exported: only the app's own alarms
 * reach it, and only with [ReminderAlarms.ACTION] or [ReminderAlarms.ACTION_CHECK_IN].
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminder = when (intent.action) {
            ReminderAlarms.ACTION -> ReminderAlarms.reminderOf(intent) ?: return
            ReminderAlarms.ACTION_CHECK_IN -> null
            else -> return
        }
        val reminders = context.reminders() ?: return
        reminders.runForReceiver(goAsync(), intent.isForeground()) {
            if (reminder != null) reminders.notifier.onAlarm(reminder)
            reminders.coordinator.rescheduleNow()
        }
    }
}

/**
 * Schedules the reminders again after a restart, an app update and a time zone change
 * (spec §11.3 rule 4): Android drops every alarm when the phone restarts. Exported for
 * these protected system broadcasts only, which no other app can send; any other action
 * (an explicit intent from another app) is ignored. If planning fails or takes too long
 * (e.g. a slow start after an update), a check-in alarm tries again in 15 minutes.
 */
class ReminderRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val reminders = context.reminders() ?: return
        reminders.runForReceiver(goAsync(), intent.isForeground()) { reminders.coordinator.rescheduleNow() }
    }

    companion object {
        val ACTIONS: Set<String> = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED)
    }
}

/** Android gives a foreground broadcast's receiver 10 s, a background one's 60 s. */
private fun Intent.isForeground(): Boolean = flags and Intent.FLAG_RECEIVER_FOREGROUND != 0

private fun Context.reminders(): Reminders? = try {
    (applicationContext as? CinexApplication)?.container?.reminders
} catch (e: Throwable) {
    // Not even the container could be made (e.g. out of memory): nothing to do now; the next start plans.
    null
}
