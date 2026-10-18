package io.github.katznmeik.cinexperience.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.katznmeik.cinexperience.core.model.PlannedReminder
import io.github.katznmeik.cinexperience.core.model.ReminderAlarmChain
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.model.ReminderLeadTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** Sets and cancels the reminders' alarms (spec §11.3 rules 4 and 5). */
interface ReminderScheduler {
    /**
     * Makes [reminders], planned at [now], the scheduled reminders ([ReminderAlarmChain]):
     * a reminder at most 10 minutes away gets its own alarm (replacing the one of the same
     * visit and kind), the others share one check-in alarm that plans again; every other
     * alarm this app set whose time is still ahead is cancelled, e.g. of a visit that was
     * deleted or changed or of a reminder switched off. An alarm whose time has passed is
     * left alone: it may still be on its way, and the check when it fires decides. Never
     * throws.
     */
    fun schedule(reminders: List<PlannedReminder>, now: Instant)

    /**
     * Sets a check-in at [at] that plans again, after planning at [now] failed (e.g. the
     * data couldn't be read in time after a restart); an earlier check-in still ahead is
     * kept. Never throws.
     */
    fun scheduleRetry(at: Instant, now: Instant)
}

/**
 * The alarms: explicit broadcasts to [ReminderAlarmReceiver] (not exported). A reminder's
 * own alarm ([ACTION]) carries the [PlannedReminder], so that it can be checked again when
 * it fires; there is one per (visit, kind), told apart by the intent's identifier, so
 * setting a reminder again replaces its alarm. The check-in ([ACTION_CHECK_IN]) is one
 * alarm for all reminders and carries nothing.
 */
internal object ReminderAlarms {
    const val ACTION: String = "io.github.katznmeik.cinexperience.action.REMINDER_ALARM"
    const val ACTION_CHECK_IN: String = "io.github.katznmeik.cinexperience.action.REMINDER_CHECK_IN"

    /** The stored key of the check-in alarm. */
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
 * [ReminderScheduler] with [AlarmManager]'s normal, inexact alarms that also fire
 * while the phone is idle (`setAndAllowWhileIdle`, `RTC_WAKEUP`): no exact-alarm
 * permission (spec §11.3 rule 5). [ReminderAlarmChain] keeps the delay small: a
 * reminder's own alarm only once it is at most 10 minutes away, before that one shared
 * check-in for all reminders (the soonest one needed), so few alarms count against
 * Android's limit on alarms while idle. Each alarm is a `FLAG_IMMUTABLE` broadcast to the
 * non-exported [ReminderAlarmReceiver].
 *
 * Android can't list an app's alarms, so the alarms set are kept in [keys]
 * (`reminder_alarms.xml`: key and trigger time, visit ids and kinds only; phone-local,
 * excluded from Auto Backup); every key is stored before its alarm is set, so each alarm
 * can be cancelled later. Every call sets all of its alarms again (alarms may be gone:
 * after a restart or a force stop), cancels the stored ones that are no longer wanted and
 * still ahead, and forgets the ones whose time has passed without cancelling them.
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
        val (own, later) = reminders.partition { ReminderAlarmChain.isFinal(it.at, now) }
        val ownAlarms = own.associateBy { ReminderAlarms.key(it.visitId, it.kind) }
        val checkIn = later.minOfOrNull { ReminderAlarmChain.checkInAt(it.at, now) }
        val wanted: Map<String, Long> = ownAlarms.mapValues { (_, reminder) -> reminder.at.toEpochMilli() } +
            listOfNotNull(checkIn?.let { ReminderAlarms.CHECK_IN_KEY to it.toEpochMilli() })
        val stored = storedAlarms()
        if (!stored.entries.containsAll(wanted.entries)) storeAlarms(stored + wanted)
        val nowMillis = now.toEpochMilli()
        // Not wanted any more: cancelled while still ahead; one whose time has passed may still be
        // on its way (an inexact alarm comes late) and is left to the check when it fires.
        for ((key, time) in stored) if (key !in wanted && time > nowMillis) cancel(key)
        for (reminder in ownAlarms.values) set(reminder.at, ReminderAlarms.intent(context, reminder))
        checkIn?.let { set(it, ReminderAlarms.checkInIntent(context)) }
        if (stored != wanted) storeAlarms(wanted)
    }

    @Synchronized
    override fun scheduleRetry(at: Instant, now: Instant) {
        val stored = storedAlarms()
        val existing = stored[ReminderAlarms.CHECK_IN_KEY]
        if (existing != null && existing > now.toEpochMilli() && existing <= at.toEpochMilli()) return
        storeAlarms(stored + (ReminderAlarms.CHECK_IN_KEY to at.toEpochMilli()))
        set(at, ReminderAlarms.checkInIntent(context))
    }

    /** The alarms this app set: key ([ReminderAlarms.key] or the check-in) → trigger time in epoch milliseconds. */
    fun storedAlarms(): Map<String, Long> = try {
        keys.getStringSet(KEY_SCHEDULED, null).orEmpty().mapNotNull { entry ->
            val time = entry.substringAfterLast('@', "").toLongOrNull() ?: return@mapNotNull null
            entry.substringBeforeLast('@') to time
        }.toMap()
    } catch (e: RuntimeException) {
        // Another type under the key: nothing known (alarms that exist are replaced by the next plan anyway).
        emptyMap()
    }

    private fun storeAlarms(value: Map<String, Long>) {
        try {
            // commit, not apply: the keys must be stored before the alarms are set.
            keys.edit(commit = true) { putStringSet(KEY_SCHEDULED, value.map { (key, time) -> "$key@$time" }.toSet()) }
        } catch (e: Throwable) {
            // Not stored: the next plan tries again.
        }
    }

    private fun set(at: Instant, intent: Intent) {
        val manager = alarmManager ?: return
        try {
            val operation = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
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
    }
}
