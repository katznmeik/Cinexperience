package io.github.katznmeik.cinexperience.feature.program

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

/** The shared-preferences filter store reads and writes on the dispatcher it is given. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FilterStoreDispatcherTest {

    /** Runs every block at once, on the calling thread, and counts them. */
    private class CountingDispatcher : CoroutineDispatcher() {
        var dispatches = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            block.run()
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun readsAndWritesRunOnTheGivenDispatcher() = runBlocking {
        val io = CountingDispatcher()
        val store = SharedPreferencesProgramFilterStore(context, io)
        val settings = ProgramFilterSettings(ProgramFilter(search = "neon"))

        assertTrue(store.write(settings))
        assertEquals(1, io.dispatches)
        assertEquals(settings, store.read())
        assertEquals(2, io.dispatches)
    }
}
