package io.github.katznmeik.cinexperience

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reminders' receivers in the merged manifests, debug and release (spec §11.3):
 * the alarm receiver isn't exported and takes no implicit intents; the restart
 * receiver is exported for the protected system broadcasts only (restart, app update,
 * time zone change).
 */
class ReminderManifestTest {

    private fun receiver(manifest: MergedManifest, simpleName: String): MergedManifest.Receiver =
        manifest.receivers.single { it.name == "io.github.katznmeik.cinexperience.reminders.$simpleName" }

    @Test
    fun theAlarmReceiverIsNotExported() {
        for (buildType in MergedManifest.BUILD_TYPES) {
            val alarm = receiver(MergedManifest.of(buildType), "ReminderAlarmReceiver")
            assertEquals(buildType, "false", alarm.exported)
            assertEquals(buildType, emptySet<String>(), alarm.actions)
        }
    }

    @Test
    fun theRestartReceiverTakesOnlyTheSystemsBroadcasts() {
        val expected = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED)
        for (buildType in MergedManifest.BUILD_TYPES) {
            val restart = receiver(MergedManifest.of(buildType), "ReminderRestartReceiver")
            assertEquals(buildType, "true", restart.exported)
            assertEquals(buildType, expected, restart.actions)
            assertNull(buildType, restart.permission)
        }
    }

    @Test
    fun noOtherReceiverOfTheAppIsExported() {
        for (buildType in MergedManifest.BUILD_TYPES) {
            val own = MergedManifest.of(buildType).receivers.filter { it.name.startsWith("io.github.katznmeik.cinexperience.") }
            assertEquals(buildType, setOf("ReminderAlarmReceiver", "ReminderRestartReceiver"), own.map { it.name.substringAfterLast('.') }.toSet())
            assertTrue(buildType, own.filter { it.exported == "true" }.all { it.name.endsWith(".ReminderRestartReceiver") })
        }
    }
}
