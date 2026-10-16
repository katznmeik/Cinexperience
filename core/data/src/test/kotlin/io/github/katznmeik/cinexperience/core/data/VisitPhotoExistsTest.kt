package io.github.katznmeik.cinexperience.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.files.FileVisitPhotoStore
import io.github.katznmeik.cinexperience.core.data.files.VisitPhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** [VisitPhotoStore.exists], which a restored visit form uses to find lost photos. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VisitPhotoExistsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = FileVisitPhotoStore(context, Dispatchers.Unconfined)
    private val folder = File(context.filesDir, VisitPhotoStore.FOLDER)

    @Test
    fun onlyStoredPhotosInsideTheFolderExist() = runTest {
        File(folder.apply { mkdirs() }, "visit-a.jpg").writeText("x")
        File(folder, "visit-dir.jpg").mkdirs()
        File(context.filesDir, "outside.jpg").writeText("x")

        assertTrue(store.exists("photos/visit-a.jpg"))
        assertFalse(store.exists("photos/visit-missing.jpg"))
        assertFalse(store.exists("photos/visit-dir.jpg"))
        assertFalse(store.exists("outside.jpg"))
        assertFalse(store.exists("photos/../outside.jpg"))

        store.delete("photos/visit-a.jpg")
        assertFalse(store.exists("photos/visit-a.jpg"))
    }
}
