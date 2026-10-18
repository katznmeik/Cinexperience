package io.github.katznmeik.cinexperience.reminders

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.katznmeik.cinexperience.R
import io.github.katznmeik.cinexperience.core.data.repository.TicketRepository
import io.github.katznmeik.cinexperience.core.data.repository.VisitRepository
import io.github.katznmeik.cinexperience.core.data.settings.ReminderSettingsStore
import io.github.katznmeik.cinexperience.core.model.PlannedReminder
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.model.ReminderSchedule
import io.github.katznmeik.cinexperience.core.model.SeatLabel
import io.github.katznmeik.cinexperience.core.model.ShowingTime
import io.github.katznmeik.cinexperience.core.model.UpcomingVisits
import io.github.katznmeik.cinexperience.core.model.qrModulesExpireAt
import io.github.katznmeik.cinexperience.core.ui.text.isolate
import io.github.katznmeik.cinexperience.core.ui.text.timeText
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Duration
import java.time.LocalTime

/**
 * The texts of the reminders (spec §11.3 e and f). Film titles (often from Yorck's
 * page or the ticket) and the names joined with other text go through [isolate], so
 * right-to-left text can't reorder the time or the seat. The booking number is never
 * in a notification.
 */
internal object ReminderNotificationTexts {
    const val SHOWTIME_CHANNEL = "Showtime reminders"
    const val SHOWTIME_CHANNEL_DESCRIPTION = "Before the showing of a visit with a start time, with its ticket"
    const val RATE_CHANNEL = "Rate your visit"
    const val RATE_CHANNEL_DESCRIPTION = "About 30 minutes after the film has ended"

    /** What the lock screen shows instead (no film, cinema or seat). */
    const val SHOWTIME_PUBLIC = "Showtime reminder"
    const val RATE_PUBLIC = "Rate your visit"

    const val RATE_TEXT = "Rate your visit"
    const val SHOW_TICKET = "Show ticket"

    /** "The Quiet Projectionist starts at 20:15" ("Your showing starts at 20:15" without a film). */
    fun showtimeTitle(filmTitle: String?, start: LocalTime): String =
        "${filmTitle?.let(::isolate) ?: "Your showing"} starts at ${timeText(start)}"

    /** "delphi LUX, Saal 4 · Row 7 · Seat 10" (without a seat: only the cinema and hall). */
    fun showtimeText(cinemaName: String, hallName: String, seat: SeatLabel?): String =
        listOfNotNull("${isolate(cinemaName)}, ${isolate(hallName)}", seat?.let { isolate(it.shortText) }).joinToString(" · ")

    /** "How was The Quiet Projectionist?" ("How was the film?" without a film). */
    fun rateTitle(filmTitle: String?): String = filmTitle?.let { "How was ${isolate(it)}?" } ?: "How was the film?"
}

/**
 * Posts a reminder when its alarm fires (spec §11.3), after checking again that it is
 * still due ([ReminderSchedule.isStillDue]: the setting is on, the visit exists with
 * the same date and start time, it isn't too late) and that Android allows the app's
 * notifications; otherwise nothing is posted.
 *
 * Two channels, so the owner can switch them separately in Android's settings:
 * "Showtime reminders" (e) and "Rate your visit" (f). Every notification is
 * `VISIBILITY_PRIVATE` with a public version that names neither film, cinema nor seat;
 * tapping it opens the visit, and "Show ticket" (e, only while the visit's QR code is
 * kept) its ticket ([ReminderIntents]). One notification per visit and kind.
 */
class ReminderNotifier(
    private val context: Context,
    private val visitRepository: VisitRepository,
    private val ticketRepository: TicketRepository,
    private val settingsStore: ReminderSettingsStore,
    private val clock: Clock,
) {
    /** Creates the two channels (again: Android keeps the owner's choices and updates only the names). */
    fun createChannels() {
        try {
            NotificationManagerCompat.from(context).createNotificationChannelsCompat(
                listOf(
                    NotificationChannelCompat.Builder(SHOWTIME_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                        .setName(ReminderNotificationTexts.SHOWTIME_CHANNEL)
                        .setDescription(ReminderNotificationTexts.SHOWTIME_CHANNEL_DESCRIPTION)
                        .build(),
                    NotificationChannelCompat.Builder(RATE_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                        .setName(ReminderNotificationTexts.RATE_CHANNEL)
                        .setDescription(ReminderNotificationTexts.RATE_CHANNEL_DESCRIPTION)
                        .build(),
                ),
            )
        } catch (e: RuntimeException) {
            // Created with the next notification.
        }
    }

    /**
     * The alarm of [reminder] fired: posts its notification if it is still due. Returns
     * whether it was posted. A repository failure is thrown (the receiver's runner,
     * [Reminders.runForReceiver], catches it: nothing is posted).
     */
    suspend fun onAlarm(reminder: PlannedReminder): Boolean {
        if (!notificationsAllowed()) return false
        val settings = settingsStore.settings.first()
        val item = visitRepository.observeAllVisits().first().firstOrNull { it.visit.id == reminder.visitId }
        val now = clock.instant()
        if (item == null || !ReminderSchedule.isStillDue(reminder, item.visit, settings, now)) return false
        val withTicket = reminder.kind == ReminderKind.BEFORE_SHOWING && item.hasTicket &&
            ticketRepository.getTicket(item.visit.id)?.qrModules != null && now.isBefore(item.visit.qrModulesExpireAt)
        val info = ReminderVisitInfo(item.visit.filmTitle, item.cinemaName, item.hallName, item.seatLabel)
        return post(reminder, notification(reminder, info, withTicket))
    }

    /** The notification of [reminder] for [item]; with [withTicket] it has "Show ticket". */
    internal fun notification(reminder: PlannedReminder, item: ReminderVisitInfo, withTicket: Boolean): Notification {
        val visitId = reminder.visitId
        val showtime = reminder.kind == ReminderKind.BEFORE_SHOWING
        val channel = if (showtime) SHOWTIME_CHANNEL_ID else RATE_CHANNEL_ID
        val title = if (showtime) {
            ReminderNotificationTexts.showtimeTitle(item.filmTitle, reminder.startTime)
        } else {
            ReminderNotificationTexts.rateTitle(item.filmTitle)
        }
        val text = if (showtime) {
            ReminderNotificationTexts.showtimeText(item.cinemaName, item.hallName, item.seatLabel)
        } else {
            ReminderNotificationTexts.RATE_TEXT
        }
        val public = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (showtime) ReminderNotificationTexts.SHOWTIME_PUBLIC else ReminderNotificationTexts.RATE_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(activity(ReminderIntents.openVisit(context, visitId)))
            .setAutoCancel(true)
        if (withTicket) {
            builder.addAction(0, ReminderNotificationTexts.SHOW_TICKET, activity(ReminderIntents.showTicket(context, visitId)))
        }
        if (showtime) {
            // Gone once the showing has long started (like the Next visit card, spec §6.1).
            val until = ShowingTime.startOf(reminder.date, reminder.startTime).plus(UpcomingVisits.NEXT_VISIT_CARD_AFTER_START)
            val left = Duration.between(clock.instant(), until).toMillis()
            if (left > 0) builder.setTimeoutAfter(left)
        }
        return builder.build()
    }

    private fun activity(intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun post(reminder: PlannedReminder, notification: Notification): Boolean {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        createChannels()
        return try {
            NotificationManagerCompat.from(context).notify(tag(reminder.visitId), id(reminder.kind), notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun notificationsAllowed(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    companion object {
        const val SHOWTIME_CHANNEL_ID: String = "showtime_reminders"
        const val RATE_CHANNEL_ID: String = "rate_your_visit"

        /** The tag of the visit's notifications (one per kind, [id]). */
        fun tag(visitId: Long): String = "visit-$visitId"

        fun id(kind: ReminderKind): Int = when (kind) {
            ReminderKind.BEFORE_SHOWING -> 1
            ReminderKind.RATE_VISIT -> 2
        }

        /** Removes the visit's notification of [kind], e.g. once "Show ticket" was tapped. */
        fun cancel(context: Context, visitId: Long, kind: ReminderKind) {
            try {
                NotificationManagerCompat.from(context).cancel(tag(visitId), id(kind))
            } catch (e: RuntimeException) {
                // Nothing to remove.
            }
        }
    }
}

/** What a reminder's texts name: the visit's film, cinema, hall and seat. */
internal data class ReminderVisitInfo(val filmTitle: String?, val cinemaName: String, val hallName: String, val seatLabel: SeatLabel?)
