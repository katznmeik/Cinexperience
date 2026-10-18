package io.github.katznmeik.cinexperience.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.katznmeik.cinexperience.core.model.PlannedReminder
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.model.ReminderLeadTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** Sets and cancels the reminders' alarms (spec §11.3 rules 4 and 5). */
interface ReminderScheduler {
    /**
     * Makes [reminders] the scheduled reminders: sets an alarm for each (replacing the
     * one of the same visit and kind) and cancels every other alarm this app set, e.g.
     * of a visit that was deleted or changed or of a reminder switched off. Never throws.
     */
    fun schedule(reminders: List<PlannedReminder>)
}

/**
 * The alarm of a reminder: an explicit broadcast to [ReminderAlarmReceiver] (not
 * exported) that carries the [PlannedReminder], so that it can be checked again when it
 * fires. One per (visit, kind): the intent's identifier tells them apart, so setting a
 * reminder again replaces its alarm.
 */
internal object ReminderAlarms {
    const val ACTION: String = "io.github.katznmeik.cinexperience.action.REMINDER_ALARM"

    private const val EXTRA_VISIT_ID = "visitId"
    private const val EXTRA_KIND = "kind"
    private const val EXTRA_AT = "at"
    private const val EXTRA_DATE = "date"
    private const val EXTRA_START_TIME = "startTime"
    private const val EXTRA_LEAD_HOURS = "leadHours"

    /** "before:12": the stored key of the alarm of [kind] for visit [visitId]. */
    fun key(visitId: Long, kind: ReminderKind): String = "${kind.code}:$visitId"

    /** The visit id and kind of [key]; `null` for a damaged one. */
    fun parseKey(key: String): Pair<Long, ReminderKind>? {
        val kind = ReminderKind.fromCode(key.substringBefore(':', "")) ?: return null
        val visitId = key.substringAfter(':', "").toLongOrNull() ?: return null
        return visitId to kind
    }

    /** The alarm's intent without its extras: what identifies it (for cancelling). */
    fun baseIntent(context: Context, visitId: Long, kind: ReminderKind): Intent =
        Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(ACTION)
            .setIdentifier("reminder:${key(visitId, kind)}")

    /** The alarm's intent for [reminder]. */
    fun intent(context: Context, reminder: PlannedReminder): Intent =
        baseIntent(context, reminder.visitId, reminder.kind)
            .putExtra(EXTRA_VISIT_ID, reminder.visitId)
            .putExtra(EXTRA_KIND, reminder.kind.code)
            .putExtra(EXTRA_AT, reminder.at.toEpochMilli())
            .putExtra(EXTRA_DATE, reminder.date.toString())
            .putExtra(EXTRA_START_TIME, reminder.startTime.toString())
            .apply { reminder.leadTime?.let { putExtra(EXTRA_LEAD_HOURS, it.hours) } }

    /**
     * The reminder an alarm's [intent] carries; `null` if it isn't one (another action,
     * a missing or malformed value).
     */
    fun reminderOf(intent: Intent?): PlannedReminder? {
        if (intent?.action != ACTION) return null
        return try {
            val visitId = intent.getLongExtra(EXTRA_VISIT_ID, 0L).takeIf { it > 0 } ?: return null
            val kind = ReminderKind.fromCode(intent.getStringExtra(EXTRA_KIND)) ?: return null
            val at = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_AT, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } ?: return null)
            val date = LocalDate.parse(intent.getStringExtra(EXTRA_DATE) ?: return null)
            val startTime = LocalTime.parse(intent.getStringExtra(EXTRA_START_TIME) ?: return null)
            val leadTime = if (kind == ReminderKind.BEFORE_SHOWING) {
                ReminderLeadTime.ofHours(intent.getIntExtra(EXTRA_LEAD_HOURS, 0)) ?: return null
            } else {
                null
            }
            PlannedReminder(visitId, kind, at, date, startTime, leadTime)
        } catch (e: DateTimeParseException) {
            null
        } catch (e: RuntimeException) {
            // Extras that can't be read (e.g. BadParcelableException).
            null
        }
    }
}

/**
 * [ReminderScheduler] with [AlarmManager]'s normal, inexact alarms that also fire
 * while the phone is idle (`setAndAllowWhileIdle`, `RTC_WAKEUP`): a reminder can come a
 * few minutes late, and the app needs no exact-alarm permission (spec §11.3 rule 5).
 * Each alarm is a `FLAG_IMMUTABLE` broadcast to the non-exported [ReminderAlarmReceiver].
 *
 * Android can't list an app's alarms, so the keys of the alarms set are kept in [keys]
 * (`reminder_alarms.xml`, visit ids and kinds only); every key is stored before its
 * alarm is set, so each alarm can be cancelled later. Every call sets all of its
 * reminders again (alarms may be gone: after a restart or a force stop) and cancels the
 * stored ones it doesn't have.
 */
class AlarmReminderScheduler(
    private val context: Context,
    private val alarmManager: AlarmManager?,
    private val keys: SharedPreferences,
) : ReminderScheduler {

    constructor(context: Context) : this(
        context.applicationContext,
        context.getSystemService(AlarmManager::class.java),
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    @Synchronized
    override fun schedule(reminders: List<PlannedReminder>) {
        val wanted = reminders.associateBy { ReminderAlarms.key(it.visitId, it.kind) }
        val stored = storedKeys()
        if (!stored.containsAll(wanted.keys)) storeKeys(stored + wanted.keys)
        for (key in stored - wanted.keys) cancel(key)
        for (reminder in wanted.values) set(reminder)
        if (stored != wanted.keys) storeKeys(wanted.keys)
    }

    /** The keys of the alarms this app set ([ReminderAlarms.key]). */
    fun storedKeys(): Set<String> = try {
        keys.getStringSet(KEY_SCHEDULED, null).orEmpty().toSet()
    } catch (e: RuntimeException) {
        // Another type under the key: nothing known (alarms that exist are replaced by the next plan anyway).
        emptySet()
    }

    private fun storeKeys(value: Set<String>) {
        try {
            // commit, not apply: the keys must be stored before the alarms are set.
            keys.edit(commit = true) { putStringSet(KEY_SCHEDULED, value.toSet()) }
        } catch (e: RuntimeException) {
            // Not stored: the next plan tries again.
        }
    }

    private fun set(reminder: PlannedReminder) {
        val manager = alarmManager ?: return
        try {
            val operation = PendingIntent.getBroadcast(
                context,
                0,
                ReminderAlarms.intent(context, reminder),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at.toEpochMilli(), operation)
        } catch (e: RuntimeException) {
            // E.g. too many alarms: this reminder is left out, the others are set.
        }
    }

    private fun cancel(key: String) {
        val (visitId, kind) = ReminderAlarms.parseKey(key) ?: return
        try {
            val operation = PendingIntent.getBroadcast(
                context,
                0,
                ReminderAlarms.baseIntent(context, visitId, kind),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: return
            alarmManager?.cancel(operation)
            operation.cancel()
        } catch (e: RuntimeException) {
            // Cancelled with the next plan, or refused when it fires (ReminderSchedule.isStillDue).
        }
    }

    companion object {
        const val PREFERENCES_NAME: String = "reminder_alarms"
        private const val KEY_SCHEDULED = "scheduled"
    }
}
