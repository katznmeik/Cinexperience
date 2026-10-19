package io.github.katznmeik.cinexperience.reminders

import android.annotation.SuppressLint
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
     * Makes [reminders], planned at [now], the scheduled reminders: each gets an alarm at
     * its time (replacing the one of the same visit and kind), except one already posted
     * ([markPosted]); every other alarm this app set whose time is still ahead is cancelled,
     * e.g. of a visit that was deleted or changed, of a reminder switched off, or a retry
     * check-in (the plan worked). An alarm whose time has passed is left alone: it may be
     * on its way, and the check when it fires decides. Never throws.
     */
    fun schedule(reminders: List<PlannedReminder>, now: Instant)

    /**
     * Sets a check-in that plans again after planning at [now] failed (e.g. the data
     * couldn't be read in time after a restart): at [at], or earlier if a check-in still
     * ahead is stored. Always set, as Android drops every alarm when the phone restarts.
     * Never throws.
     */
    fun scheduleRetry(at: Instant, now: Instant)

    /**
     * [reminder] was posted: no plan sets its alarm again while its time hasn't passed
     * (an alarm delivered a moment early would otherwise be planned and posted twice).
     * Never throws.
     */
    fun markPosted(reminder: PlannedReminder)
}

/**
 * The alarms: explicit broadcasts to [ReminderAlarmReceiver] (not exported). A reminder's
 * alarm ([ACTION]) carries the [PlannedReminder], so that it can be checked again when it
 * fires; there is one per (visit, kind), told apart by the intent's identifier, so setting
 * a reminder again replaces its alarm. The retry check-in ([ACTION_CHECK_IN], after a
 * failed plan) is one alarm and carries nothing.
 */
internal object ReminderAlarms {
    const val ACTION: String = "io.github.katznmeik.cinexperience.action.REMINDER_ALARM"
    const val ACTION_CHECK_IN: String = "io.github.katznmeik.cinexperience.action.REMINDER_CHECK_IN"

    /** The stored key of the retry check-in alarm. */
    const val CHECK_IN_KEY: String = "checkin"

    private const val EXTRA_VISIT_ID = "visitId"
    private const val EXTRA_KIND = "kind"
    private const val EXTRA_AT = "at"
    private const val EXTRA_DATE = "date"
    private const val EXTRA_START_TIME = "startTime"
    private const val EXTRA_LEAD_HOURS = "leadHours"

    /** "before:12": the stored key of the alarm of [kind] for visit [visitId]. */
    fun key(visitId: Long, kind: ReminderKind): String = "${kind.code}:$visitId"

    /** The visit id and kind of a reminder's [key]; `null` for the check-in or a damaged one. */
    fun parseKey(key: String): Pair<Long, ReminderKind>? {
        val kind = ReminderKind.fromCode(key.substringBefore(':', "")) ?: return null
        val visitId = key.substringAfter(':', "").toLongOrNull() ?: return null
        return visitId to kind
    }

    /** The reminder alarm's intent without its extras: what identifies it (for cancelling). */
    fun baseIntent(context: Context, visitId: Long, kind: ReminderKind): Intent =
        Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(ACTION)
            .setIdentifier("reminder:${key(visitId, kind)}")

    /** The reminder alarm's intent for [reminder]. */
    fun intent(context: Context, reminder: PlannedReminder): Intent =
        baseIntent(context, reminder.visitId, reminder.kind)
            .putExtra(EXTRA_VISIT_ID, reminder.visitId)
            .putExtra(EXTRA_KIND, reminder.kind.code)
            .putExtra(EXTRA_AT, reminder.at.toEpochMilli())
            .putExtra(EXTRA_DATE, reminder.date.toString())
            .putExtra(EXTRA_START_TIME, reminder.startTime.toString())
            .apply { reminder.leadTime?.let { putExtra(EXTRA_LEAD_HOURS, it.hours) } }

    /** The check-in's intent. */
    fun checkInIntent(context: Context): Intent =
        Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_CHECK_IN).setIdentifier("reminder:$CHECK_IN_KEY")

    /**
     * The reminder a reminder alarm's [intent] carries; `null` if it isn't one (another
     * action, a missing or malformed value).
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
 * [ReminderScheduler] with [AlarmManager]'s exact alarms that also fire while the phone
 * is idle (`setExactAndAllowWhileIdle`, `RTC_WAKEUP`; spec §11.3 rule 5), one per
 * reminder at its time, so a reminder comes on time also when the app hasn't been opened
 * for days. The app holds `USE_EXACT_ALARM` (granted at install); should Android ever
 * refuse exact alarms ([AlarmManager.canScheduleExactAlarms] false), it falls back to
 * normal ones (`setAndAllowWhileIdle`), which may come late. Each alarm is a
 * `FLAG_IMMUTABLE` broadcast to the non-exported [ReminderAlarmReceiver].
 *
 * Android can't list an app's alarms, so the alarms set are kept in [keys]
 * (`reminder_alarms.xml`: key and trigger time, visit ids and kinds only; phone-local,
 * excluded from Auto Backup), with the reminders already posted; every key is stored
 * before its alarm is set, so each alarm can be cancelled later. Every call sets all of
 * its alarms again (alarms may be gone: after a restart or a force stop), cancels the
 * stored ones that are no longer wanted and still ahead, and forgets the ones whose time
 * has passed without cancelling them.
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
    override fun schedule(reminders: List<PlannedReminder>, now: Instant) {
        val nowMillis = now.toEpochMilli()
        // Posted reminders are remembered until their time has passed (then no plan has them).
        val storedPosted = read(KEY_POSTED)
        val posted = storedPosted.filterValues { it > nowMillis }
        val due = reminders.associateBy { ReminderAlarms.key(it.visitId, it.kind) }
            .filter { (key, reminder) -> posted[key] != reminder.at.toEpochMilli() }
        val wanted: Map<String, Long> = due.mapValues { (_, reminder) -> reminder.at.toEpochMilli() }
        val stored = storedAlarms()
        if (!stored.entries.containsAll(wanted.entries)) write(KEY_SCHEDULED, stored + wanted)
        // Not wanted any more: cancelled while still ahead; one whose time has passed may still be
        // on its way and is left to the check when it fires.
        for ((key, time) in stored) if (key !in wanted && time > nowMillis) cancel(key)
        for (reminder in due.values) set(reminder.at, ReminderAlarms.intent(context, reminder))
        if (stored != wanted) write(KEY_SCHEDULED, wanted)
        if (storedPosted != posted) write(KEY_POSTED, posted)
    }

    @Synchronized
    override fun scheduleRetry(at: Instant, now: Instant) {
        val stored = storedAlarms()
        val earlier = stored[ReminderAlarms.CHECK_IN_KEY]?.takeIf { it > now.toEpochMilli() && it < at.toEpochMilli() }
        val time = earlier?.let(Instant::ofEpochMilli) ?: at
        write(KEY_SCHEDULED, stored + (ReminderAlarms.CHECK_IN_KEY to time.toEpochMilli()))
        set(time, ReminderAlarms.checkInIntent(context))
    }

    @Synchronized
    override fun markPosted(reminder: PlannedReminder) {
        write(KEY_POSTED, read(KEY_POSTED) + (ReminderAlarms.key(reminder.visitId, reminder.kind) to reminder.at.toEpochMilli()))
    }

    /** The alarms this app set: key ([ReminderAlarms.key] or the check-in) → trigger time in epoch milliseconds. */
    fun storedAlarms(): Map<String, Long> = read(KEY_SCHEDULED)

    /** The reminders posted whose time hasn't passed yet: key → their time in epoch milliseconds. */
    fun postedReminders(): Map<String, Long> = read(KEY_POSTED)

    private fun read(name: String): Map<String, Long> = try {
        keys.getStringSet(name, null).orEmpty().mapNotNull { entry ->
            val time = entry.substringAfterLast('@', "").toLongOrNull() ?: return@mapNotNull null
            entry.substringBeforeLast('@') to time
        }.toMap()
    } catch (e: RuntimeException) {
        // Another type under the key: nothing known (alarms that exist are replaced by the next plan anyway).
        emptyMap()
    }

    private fun write(name: String, value: Map<String, Long>) {
        try {
            // commit, not apply: the keys must be stored before the alarms are set.
            keys.edit(commit = true) { putStringSet(name, value.map { (key, time) -> "$key@$time" }.toSet()) }
        } catch (e: Throwable) {
            // Not stored: the next plan tries again.
        }
    }

    // Lint knows only SCHEDULE_EXACT_ALARM for exact alarms; the app holds USE_EXACT_ALARM,
    // which Android accepts as well (spec §11.3 rule 6), checks canScheduleExactAlarms() and
    // catches the SecurityException.
    @SuppressLint("MissingPermission")
    private fun set(at: Instant, intent: Intent) {
        val manager = alarmManager ?: return
        try {
            val operation = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val exact = try {
                if (manager.canScheduleExactAlarms()) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
                    true
                } else {
                    false
                }
            } catch (e: SecurityException) {
                // Exact alarms refused after all: a normal one below.
                false
            }
            if (!exact) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
        } catch (e: Throwable) {
            // E.g. too many alarms: this one is left out, the others are set.
        }
    }

    private fun cancel(key: String) {
        val intent = if (key == ReminderAlarms.CHECK_IN_KEY) {
            ReminderAlarms.checkInIntent(context)
        } else {
            val (visitId, kind) = ReminderAlarms.parseKey(key) ?: return
            ReminderAlarms.baseIntent(context, visitId, kind)
        }
        try {
            val operation = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                ?: return
            alarmManager?.cancel(operation)
            operation.cancel()
        } catch (e: Throwable) {
            // Cancelled with the next plan, or refused when it fires (ReminderSchedule.isStillDue).
        }
    }

    companion object {
        const val PREFERENCES_NAME: String = "reminder_alarms"
        private const val KEY_SCHEDULED = "scheduled"
        private const val KEY_POSTED = "posted"
    }
}
