package io.github.katznmeik.cinexperience.core.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The backup manifest's `appVersion` is the version the app container passes in,
 * the same one Settings → About shows (one version source).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataContainerAppVersionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theBackupNamesTheAppVersionTheContainerWasGiven() = runTest {
        val data = DataContainer(context, backgroundScope, "9.8.7", Dispatchers.Default, Dispatchers.IO)
        val file = File(context.cacheDir, "version-test.zip").apply { delete() }
        val uri = Uri.fromFile(file).toString()

        data.backupRepository.export(uri)

        assertEquals("9.8.7", data.backupRepository.prepareImport(uri).appVersion)
    }
}
