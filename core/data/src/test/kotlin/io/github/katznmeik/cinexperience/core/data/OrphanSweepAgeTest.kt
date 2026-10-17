package io.github.katznmeik.cinexperience.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.files.FileVisitPhotoStore
import io.github.katznmeik.cinexperience.core.data.files.ReferenceImageStore
import io.github.katznmeik.cinexperience.core.data.files.VisitPhotoStore
import io.github.katznmeik.cinexperience.core.model.Visit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/**
 * The startup sweep's minimum ages: an unsaved visit photo may belong to a visit
 * form that is restored after process death, so it is kept for days; a reference
 * screenshot only for an hour (the layout editor's draft doesn't survive process
 * death). Referenced files are never deleted, however old.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OrphanSweepAgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val photos = File(context.filesDir, VisitPhotoStore.FOLDER)
    private val references = File(context.filesDir, ReferenceImageStore.FOLDER)
    private val captures = File(context.cacheDir, FileVisitPhotoStore.CAPTURE_FOLDER)
    private val now = System.currentTimeMillis()

    private fun file(dir: File, name: String, ageMs: Long): File = File(dir.apply { mkdirs() }, name).also {
        it.writeText("x")
        it.setLastModified(now - ageMs)
    }

    private fun data(scope: CoroutineScope) = DataContainer(context, scope, "1.2.3", Dispatchers.Default, Dispatchers.IO)

    @Test
    fun anUnsavedVisitPhotoOutlivesALongFilm() = runTest {
        val photo = file(photos, "visit-unsaved.jpg", ageMs = 2 * HOUR)

        assertEquals(0, data(backgroundScope).sweepOrphanFiles(now))
        assertTrue(photo.exists())
    }

    @Test
    fun anUnreferencedVisitPhotoIsDeletedAfterAWeek() = runTest {
        val old = file(photos, "visit-old.jpg", ageMs = 8 * DAY)
        val sixDays = file(photos, "visit-six-days.jpg", ageMs = 6 * DAY)

        assertEquals(1, data(backgroundScope).sweepOrphanFiles(now))
        assertFalse(old.exists())
        assertTrue(sixDays.exists())
    }

    @Test
    fun aLeftoverCaptureFollowsTheVisitPhotoAge() = runTest {
        val fresh = file(captures, "capture-fresh.jpg", ageMs = 2 * HOUR)
        val old = file(captures, "capture-old.jpg", ageMs = 8 * DAY)

        assertEquals(1, data(backgroundScope).sweepOrphanFiles(now))
        assertTrue(fresh.exists())
        assertFalse(old.exists())
    }

    @Test
    fun anUnreferencedReferenceImageIsStillDeletedAfterAnHour() = runTest {
        val old = file(references, "hall-1-old.jpg", ageMs = 2 * HOUR)
        val fresh = file(references, "hall-1-fresh.jpg", ageMs = 10 * MINUTE)

        assertEquals(1, data(backgroundScope).sweepOrphanFiles(now))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun referencedFilesAreNeverDeleted() = runTest {
        val data = data(backgroundScope)
        val photo = file(photos, "visit-saved.jpg", ageMs = 30 * DAY)
        val reference = file(references, "hall-9-saved.jpg", ageMs = 30 * DAY)
        val halls = data.hallRepository
        val hallId = halls.createHall(data.cinemaRepository.createCinema("Kino"), "Saal 1")
        halls.updateHall(halls.getHall(hallId)!!.copy(referenceImagePath = "reference/hall-9-saved.jpg"))
        data.visitRepository.saveVisit(Visit(hallId = hallId, date = LocalDate.of(2026, 10, 1), photoPath = "photos/visit-saved.jpg"))

        assertEquals(0, data.sweepOrphanFiles(now))
        assertTrue(photo.exists())
        assertTrue(reference.exists())
    }

    private companion object {
        const val MINUTE = 60 * 1000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
