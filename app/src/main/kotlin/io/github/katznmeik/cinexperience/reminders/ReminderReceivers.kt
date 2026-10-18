package io.github.katznmeik.cinexperience.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.katznmeik.cinexperience.CinexApplication

/**
 * A reminder's alarm fired (spec §11.3): checks it again and posts its notification
 * ([ReminderNotifier.onAlarm]), then schedules again, so the reminders beyond the
 * plan's limit follow. Not exported: only the app's own alarms reach it, and only
 * with [ReminderAlarms.ACTION].
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminder = ReminderAlarms.reminderOf(intent) ?: return
        val reminders = context.reminders() ?: return
        reminders.runForReceiver(goAsync()) {
            reminders.notifier.onAlarm(reminder)
            reminders.coordinator.rescheduleNow()
        }
    }
}

/**
 * Schedules the reminders again after a restart, an app update and a time zone change
 * (spec §11.3 rule 4): Android drops every alarm when the phone restarts. Exported for
 * these protected system broadcasts only, which no other app can send; any other action
 * (an explicit intent from another app) is ignored.
 */
class ReminderRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val reminders = context.reminders() ?: return
        reminders.runForReceiver(goAsync()) { reminders.coordinator.rescheduleNow() }
    }

    companion object {
        val ACTIONS: Set<String> = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED)
    }
}

private fun Context.reminders(): Reminders? = (applicationContext as? CinexApplication)?.container?.reminders
