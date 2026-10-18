package io.github.katznmeik.cinexperience.reminders

import io.github.katznmeik.cinexperience.core.model.PlannedReminder
import java.time.Instant
import java.util.Collections

/** A [ReminderScheduler] that records every plan and retry it is given (no alarms). */
class RecordingReminderScheduler : ReminderScheduler {
    /** Every plan, oldest first. */
    val plans: MutableList<List<PlannedReminder>> = Collections.synchronizedList(mutableListOf())

    /** The times of the retry check-ins asked for, oldest first. */
    val retries: MutableList<Instant> = Collections.synchronizedList(mutableListOf())

    /** The plan of the last run, `null` before the first. */
    val last: List<PlannedReminder>? get() = plans.lastOrNull()

    override fun schedule(reminders: List<PlannedReminder>, now: Instant) {
        plans += reminders
    }

    override fun scheduleRetry(at: Instant, now: Instant) {
        retries += at
    }
}
