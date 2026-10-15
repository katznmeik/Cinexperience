package io.github.katznmeik.cinexperience.feature.program

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The filters in shared preferences, and how links reach the browser. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StoreAndBrowserTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences(SharedPreferencesProgramFilterStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val store = SharedPreferencesProgramFilterStore(prefs)

    @Test
    fun filtersSurviveTheStore() = runBlocking {
        val settings = ProgramFilterSettings(ProgramFilter(search = "neon"), listOf(SavedFilter("Late", ProgramFilter(day = DayChoice.WEEKEND))))
        assertTrue(store.write(settings))
        assertEquals(settings, SharedPreferencesProgramFilterStore(context).read())
    }

    @Test
    fun aValueOfAnotherTypeReadsAsTheDefaults() = runBlocking {
        prefs.edit().putInt("filters", 3).commit()
        assertEquals(ProgramFilterSettings(), store.read())
        prefs.edit().putString("filters", "not json").commit()
        assertEquals(ProgramFilterSettings(), store.read())
    }

    @Test
    fun oneBadSavedFilterDoesNotWipeTheOthers() = runBlocking {
        prefs.edit().putString(
            "filters",
            """{"current":{"day":"TOMORROW"},"saved":[{"name":"A","filter":{}},{"name":"B","filter":7},{"name":"C","filter":{"from":"x"}}]}""",
        ).commit()
        val settings = store.read()
        assertEquals(DayChoice.TOMORROW, settings.current.day)
        assertEquals(listOf("A", "C"), settings.saved.map { it.name })
    }

    private val link = "https://www.yorck.de/en/films/neon-harbour"

    @Test
    fun linksGoToTheBrowserFirst() {
        val started = mutableListOf<Intent>()
        assertTrue(BrowserLinks.open(link) { started += it })
        val intent = started.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(link, intent.dataString)
        val selector = assertNotNullAndGet(intent.selector)
        assertEquals(Intent.ACTION_MAIN, selector.action)
        assertTrue(selector.hasCategory(Intent.CATEGORY_APP_BROWSER))
    }

    @Test
    fun withoutABrowserForTheSelectorThePlainViewIsTried() {
        val started = mutableListOf<Intent>()
        val opened = BrowserLinks.open(link) { intent ->
            if (intent.selector != null) throw ActivityNotFoundException()
            started += intent
        }
        assertTrue(opened)
        assertNull(started.single().selector)
        assertEquals(link, started.single().dataString)
    }

    @Test
    fun nothingToOpenItWith() {
        assertFalse(BrowserLinks.open(link) { throw ActivityNotFoundException() })
    }

    @Test
    fun onlyYorckLinksAreOpened() {
        var starts = 0
        assertFalse(BrowserLinks.open("https://example.com/x") { starts++ })
        assertFalse(BrowserLinks.open("http://www.yorck.de/en/films") { starts++ })
        assertEquals(0, starts)
    }

    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
