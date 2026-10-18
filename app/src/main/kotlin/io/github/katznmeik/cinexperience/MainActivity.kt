package io.github.katznmeik.cinexperience

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katznmeik.cinexperience.core.model.ReminderKind
import io.github.katznmeik.cinexperience.core.ui.theme.CinexTheme
import io.github.katznmeik.cinexperience.reminders.IncomingReminderViewModel
import io.github.katznmeik.cinexperience.reminders.ReminderNotifier
import io.github.katznmeik.cinexperience.ui.CinexApp

/**
 * The app's only activity. Besides the launcher it receives ticket PDFs (spec
 * §6.10): "Share" (`ACTION_SEND`) and "Open with" (`ACTION_VIEW`), see the manifest.
 * Each such intent is imported once: in [onCreate] only for a new activity (not after
 * rotation or process recreation, which bring `savedInstanceState`; not when it is
 * launched again from the recent apps, `TicketIntents`), and through [onNewIntent]
 * when the activity is reused. The launch mode is the standard one, so a share from
 * the email app opens a new instance in the email app's task.
 *
 * A reminder's notification (spec §11.3) opens the visit or its ticket through
 * `ReminderIntents` the same way: once, in the app's own task (`onNewIntent` when the
 * activity shows already), only once no backup import runs, and only with the
 * installation's token (`ReminderIntentToken`), which another app's intent lacks.
 */
class MainActivity : ComponentActivity() {

    private val incomingTicket: IncomingTicketViewModel by viewModels()
    private val incomingReminder: IncomingReminderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen (Theme.Cinexperience.Starting) until the first frame; it then
        // switches to Theme.Cinexperience. The data loads in the background meanwhile.
        installSplashScreen()
        // Edge-to-edge with light icons on dark, transparent system bars (dark theme only).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as CinexApplication).container
        container.onAppStart()
        if (savedInstanceState == null) offer(intent)
        setContent {
            val pendingTicket by incomingTicket.pending.collectAsStateWithLifecycle()
            val pendingReminder by incomingReminder.pending.collectAsStateWithLifecycle()
            CinexTheme {
                CinexApp(
                    container,
                    pendingTicketImport = pendingTicket,
                    onPendingTicketImportOpened = incomingTicket::consume,
                    pendingReminder = pendingReminder,
                    onPendingReminderOpened = incomingReminder::consume,
                )
            }
        }
    }

    private fun offer(intent: Intent?) {
        incomingTicket.offer(intent, packageName)
        val reminder = incomingReminder.offer(intent, (application as CinexApplication).container.reminders.intentToken)
        // "Show ticket" is a button of the notification, which stays otherwise (a tap on the notification removes it).
        if (reminder != null && reminder.showTicket) ReminderNotifier.cancel(this, reminder.visitId, ReminderKind.BEFORE_SHOWING)
    }

    /** A ticket shared or opened, or a reminder tapped, while the activity runs (if Android reuses it). Public for the activity tests. */
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offer(intent)
    }
}
