package io.github.katznmeik.cinexperience

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The permissions of the merged manifest (the app's and every library's) are
 * exactly the expected ones (spec §11.1, §11.3 rule 6): INTERNET only from
 * core:program, the reminders' notifications and restart broadcast (no exact
 * alarms), and AndroidX's app-private receiver permission. A dependency that adds a
 * permission fails the build here, in debug and in release.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppPermissionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun expectedPermissions(packageName: String) = setOf(
        // core:program, the Program download (spec §11.1).
        "android.permission.INTERNET",
        // The reminders (spec §11.3 rule 6): notifications, asked at runtime, and the
        // restart broadcast, after which their alarms are set again. Normal, inexact
        // alarms: no SCHEDULE_EXACT_ALARM or USE_EXACT_ALARM.
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        // androidx.core: protects the app's own dynamically registered receivers.
        "$packageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    )

    @Test
    fun theAppRequestsExactlyTheExpectedPermissions() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertEquals(expectedPermissions(context.packageName), info.requestedPermissions.orEmpty().toSet())
    }

    @Test
    fun theDebugAndReleaseManifestsRequestExactlyTheExpectedPermissions() {
        // CI runs only the debug unit tests: a permission from a release-only manifest or library shows here.
        for (buildType in MergedManifest.BUILD_TYPES) {
            val manifest = MergedManifest.of(buildType)
            assertEquals(buildType, expectedPermissions(manifest.packageName), manifest.requestedPermissions)
        }
    }
}
