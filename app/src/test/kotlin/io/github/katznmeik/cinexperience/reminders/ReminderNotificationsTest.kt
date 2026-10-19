package io.github.katznmeik.cinexperience.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.katznmeik.cinexperience.MainActivity
import io.github.katznmeik.cinexperience.core.model.PlannedReminder
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.model.ReminderLeadTime
import io.github.katznmeik.cinexperience.core.model.ReminderSettings
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReminderSettingsStore
import io.github.katznmeik.cinexperience.core.testing.FakeStore
import io.github.katznmeik.cinexperience.core.testing.MutableClock
import io.github.katznmeik.cinexperience.core.testing.SampleTickets
import io.github.katznmeik.cinexperience.ui.FakeAppContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The reminders' notifications (spec §11.3 e and f): texts, the two channels, "Show
 * ticket" only while the visit's QR code is kept, the intents to the visit and the
 * ticket, and privacy (a public version without film, cinema or seat; never the booking
 * number). Invented data: "The Quiet Projectionist" at delphi LUX, Saal 4, Row 7 ·
 * Seat 10, Saturday 17 Oct 2026 at 20:15, booking number TEST123.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderNotificationsTest {

    private val context = RuntimeEnvironment.getApplication()
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))
    private val clock = MutableClock(Instant.parse("2026-10-17T16:16:00Z"))
    private val visit = SampleTickets.VISIT
    private val date = LocalDate.of(2026, 10, 17)

    private val showtime =
        PlannedReminder(visit, ReminderKind.BEFORE_SHOWING, Instant.parse("2026-10-17T16:15:00Z"), date, SampleTickets.START, ReminderLeadTime.TWO_HOURS)
    private val rate = PlannedReminder(visit, ReminderKind.RATE_VISIT, Instant.parse("2026-10-17T21:15:00Z"), date, SampleTickets.START)

    private fun post(reminder: PlannedReminder, store: FakeStore = SampleTickets.store()): Notification? {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val settings = FakeReminderSettingsStore(ReminderSettings(beforeShowing = true, rateAfterVisit = true))
        val container = FakeAppContainer(FakeCinexRepository(store), clock = clock, reminderSettingsStore = settings)
        token = container.reminders.intentToken
        assertTrue(runBlocking { container.reminders.notifier.onAlarm(reminder) })
        return notifications.getNotification(ReminderNotifier.tag(visit), ReminderNotifier.id(reminder.kind))
    }

    /** The token of the last container (the same for all: it lives in the app's preferences). */
    private var token: ReminderIntentToken? = null

    private val Notification.title: String? get() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private val Notification.text: String? get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun PendingIntent.intent(): Intent = shadowOf(this).savedIntent.also { assertTrue("immutable", shadowOf(this).isImmutable) }

    private fun assertOpens(pendingIntent: PendingIntent, action: String) {
        val intent = pendingIntent.intent()
        assertTrue(shadowOf(pendingIntent).isActivity)
        assertEquals(ComponentName(context, MainActivity::class.java), intent.component)
        assertEquals(action, intent.action)
        assertEquals(visit, intent.getLongExtra(ReminderIntents.EXTRA_VISIT_ID, 0))
        // With the installation's token, so MainActivity takes it from this notification only.
        val carried = intent.getStringExtra(ReminderIntents.EXTRA_TOKEN)
        assertTrue(checkNotNull(token).matches(carried))
        assertEquals(32, carried!!.length)
    }

    /** Every text the notification and its public version hold. */
    @Suppress("DEPRECATION")
    private fun Notification.allTexts(): List<String> = listOfNotNull(this, publicVersion).flatMap { n ->
        n.extras.keySet().mapNotNull { key -> n.extras.get(key)?.toString() } + n.actions.orEmpty().map { it.title.toString() }
    }

    @Test
    fun theShowtimeReminderNamesFilmTimeCinemaHallAndSeat() {
        val notification = checkNotNull(post(showtime))
        assertEquals("\u2068${SampleTickets.FILM}\u2069 starts at 20:15", notification.title)
        assertEquals("\u2068delphi LUX\u2069, \u2068Saal 4\u2069 · \u2068Row 7 · Seat 10\u2069", notification.text)
        assertEquals(ReminderNotifier.SHOWTIME_CHANNEL_ID, notification.channelId)
        assertEquals(NotificationCompat.CATEGORY_REMINDER, notification.category)
        assertTrue("removed when tapped", notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue("posted again (the same visit and kind), it doesn't sound again", notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertOpens(notification.contentIntent, ReminderIntents.ACTION_OPEN_VISIT)
        // Gone 30 minutes after the start (20:45 Berlin), like the Next visit card.
        assertEquals(Duration.between(clock.now, Instant.parse("2026-10-17T18:45:00Z")).toMillis(), notification.timeoutAfter)
    }

    @Test
    fun showTicketOnlyWhileTheQrCodeIsKept() {
        val withCode = checkNotNull(post(showtime))
        assertEquals(listOf("Show ticket"), withCode.actions.orEmpty().map { it.title.toString() })
        assertOpens(withCode.actions.single().actionIntent, ReminderIntents.ACTION_SHOW_TICKET)

        val codeRemoved = SampleTickets.store(tickets = listOf(SampleTickets.ticket(qr = null)))
        assertNull(checkNotNull(post(showtime, codeRemoved)).actions)
        val noTicket = SampleTickets.store(tickets = emptyList())
        assertNull(checkNotNull(post(showtime, noTicket)).actions)
    }

    @Test
    fun rateYourVisitAsksHowTheFilmWas() {
        clock.now = Instant.parse("2026-10-17T21:16:00Z")
        val notification = checkNotNull(post(rate))
        assertEquals("How was \u2068${SampleTickets.FILM}\u2069?", notification.title)
        assertEquals("Rate your visit", notification.text)
        assertEquals(ReminderNotifier.RATE_CHANNEL_ID, notification.channelId)
        assertNull("no ticket button", notification.actions)
        assertOpens(notification.contentIntent, ReminderIntents.ACTION_OPEN_VISIT)
        assertEquals(0L, notification.timeoutAfter)
    }

    @Test
    fun withoutAFilmTitleTheTextsStillRead() {
        val untitled = SampleTickets.store(visits = listOf(SampleTickets.visit(date).copy(filmTitle = null, seatId = null)))
        val showtimeNotification = checkNotNull(post(showtime, untitled))
        assertEquals("Your showing starts at 20:15", showtimeNotification.title)
        assertEquals("\u2068delphi LUX\u2069, \u2068Saal 4\u2069", showtimeNotification.text)
        clock.now = Instant.parse("2026-10-17T21:16:00Z")
        assertEquals("How was the film?", checkNotNull(post(rate, untitled)).title)
    }

    @Test
    fun theLockScreenShowsNoFilmCinemaOrSeatAndNoNotificationTheBookingNumber() {
        val showtimeNotification = checkNotNull(post(showtime))
        clock.now = Instant.parse("2026-10-17T21:16:00Z")
        val rateNotification = checkNotNull(post(rate))
        for ((notification, publicTitle) in listOf(showtimeNotification to "Showtime reminder", rateNotification to "Rate your visit")) {
            assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
            val public = checkNotNull(notification.publicVersion)
            assertEquals(publicTitle, public.title)
            assertNull(public.text)
            @Suppress("DEPRECATION")
            val publicTexts = public.extras.keySet().mapNotNull { public.extras.get(it)?.toString() }.joinToString("\n")
            for (secret in listOf(SampleTickets.FILM, "delphi", "Saal", "Seat", "Row")) assertFalse(secret, publicTexts.contains(secret))
            // Never the booking number, in any text of either version.
            assertTrue(notification.allTexts().none { it.contains(SampleTickets.BOOKING_NUMBER.value) })
        }
    }

    @Test
    fun withoutAStoredTokenTheNotificationHasNoTapTargetsAndTheNextOneTriesAgain() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val preferences = FailingCommits(context.getSharedPreferences("token_test", Context.MODE_PRIVATE), failures = 1)
        val intentToken = ReminderIntentToken { preferences }
        val repository = FakeCinexRepository(SampleTickets.store())
        val settings = FakeReminderSettingsStore(ReminderSettings(beforeShowing = true, rateAfterVisit = true))
        val notifier = ReminderNotifier(context, repository, repository, settings, intentToken, clock)
        fun posted() = checkNotNull(notifications.getNotification(ReminderNotifier.tag(visit), ReminderNotifier.id(ReminderKind.BEFORE_SHOWING)))

        // The token couldn't be stored: MainActivity wouldn't open anything, so nothing to tap.
        assertTrue(runBlocking { notifier.onAlarm(showtime) })
        assertNull(posted().contentIntent)
        assertNull("no Show ticket, although the QR code is kept", posted().actions)
        assertEquals("\u2068${SampleTickets.FILM}\u2069 starts at 20:15", posted().title)

        // The next notification stores it and opens the visit.
        assertTrue(runBlocking { notifier.onAlarm(showtime) })
        token = intentToken
        assertOpens(posted().contentIntent, ReminderIntents.ACTION_OPEN_VISIT)
        assertOpens(posted().actions.single().actionIntent, ReminderIntents.ACTION_SHOW_TICKET)
    }

    @Test
    fun twoChannelsTheOwnerCanSwitchSeparately() {
        post(showtime)
        val channels = notifications.notificationChannels.associate { it.id to it.name.toString() }
        assertEquals(
            mapOf(ReminderNotifier.SHOWTIME_CHANNEL_ID to "Showtime reminders", ReminderNotifier.RATE_CHANNEL_ID to "Rate your visit"),
            channels,
        )
        val rateChannel = checkNotNull(context.getSystemService(NotificationManager::class.java).getNotificationChannel(ReminderNotifier.RATE_CHANNEL_ID))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, rateChannel.importance)
    }
}
