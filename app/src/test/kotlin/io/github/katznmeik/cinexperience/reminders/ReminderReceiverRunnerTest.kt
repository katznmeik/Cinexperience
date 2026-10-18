package io.github.katznmeik.cinexperience.reminders

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.testing.FakeBackupRepository
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import io.github.katznmeik.cinexperience.core.testing.FakeProgramRepository
import io.github.katznmeik.cinexperience.core.testing.FakeReminderSettingsStore
import io.github.katznmeik.cinexperience.core.testing.SampleProgram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.ZoneOffset

/**
 * The receivers' background work (spec §11.3 rule 4): 8 s for a foreground broadcast,
 * 50 s for a background one (a restart, an app update: slow startup work fits); if
 * planning fails, throws (even an `Error`) or runs out of time, a check-in in 15 minutes
 * plans again. Nothing escapes into the app's scope.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderReceiverRunnerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scheduler = RecordingReminderScheduler()
    private val clock = Clock.fixed(SampleProgram.FETCHED_AT, ZoneOffset.UTC)
    private val retryAt = SampleProgram.FETCHED_AT.plusSeconds(15 * 60)

    private fun reminders(scope: CoroutineScope): Reminders {
        val repository = FakeCinexRepository()
        val settings = FakeReminderSettingsStore()
        val token = ReminderIntentToken(context)
        return Reminders(
            ReminderCoordinator(repository, repository, FakeProgramRepository(), settings, FakeBackupRepository().isImporting, scheduler, clock),
            ReminderNotifier(context, repository, repository, settings, token, clock),
            token,
            scope,
        )
    }

    private fun TestScope.runner() = reminders(backgroundScope)

    @Test
    fun aBackgroundBroadcastGetsFiftySecondsThenARetry() = runTest {
        runner().runForReceiver(null, foreground = false) { awaitCancellation() }
        advanceTimeBy(49_000)
        runCurrent()
        assertTrue(scheduler.retries.isEmpty())
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(retryAt), scheduler.retries)
    }

    @Test
    fun aForegroundBroadcastGetsEightSeconds() = runTest {
        runner().runForReceiver(null, foreground = true) { awaitCancellation() }
        advanceTimeBy(7_000)
        runCurrent()
        assertTrue(scheduler.retries.isEmpty())
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(retryAt), scheduler.retries)
    }

    @Test
    fun aFailedOrThrowingPlanSetsARetryAndNothingEscapes() = runTest {
        val runner = runner()
        runner.runForReceiver(null, foreground = false) { false }
        runner.runForReceiver(null, foreground = false) { throw IllegalStateException("database") }
        runner.runForReceiver(null, foreground = false) { throw OutOfMemoryError("test") }
        runCurrent()
        assertEquals(listOf(retryAt, retryAt, retryAt), scheduler.retries)
    }

    @Test
    fun aPlanThatWorkedSetsNoRetry() = runTest {
        runner().runForReceiver(null, foreground = false) { true }
        runCurrent()
        assertTrue(scheduler.retries.isEmpty())
    }
}
