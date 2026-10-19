package io.github.katznmeik.cinexperience.reminders

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Makes every write of the app's own `SharedPreferences` fail, as on a full or broken
 * disk: the `shared_prefs` folder is replaced by a file. Android's `SharedPreferences`
 * then return false from `commit()` and, as on a phone, keep the value in memory.
 */
internal class BrokenPreferencesFolder(context: Context) {
    private val folder = File(context.dataDir, "shared_prefs")

    fun breakIt() {
        folder.deleteRecursively()
        check(folder.createNewFile())
    }

    fun repair() {
        if (folder.isFile) check(folder.delete())
        folder.mkdirs()
    }
}

/**
 * The notification intents' token ([ReminderIntentToken]) with Android's real
 * `SharedPreferences`: created once, stored, the same in a later process; one that
 * couldn't be stored is never handed out or cached (`null`, tried again next time), also
 * not from the memory where Android keeps a failed commit's value, so no notification
 * carries a token MainActivity doesn't know.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderIntentTokenTest {

    private val context = RuntimeEnvironment.getApplication()
    private val folder = BrokenPreferencesFolder(context)
    private val preferences get() = context.getSharedPreferences("token_test", Context.MODE_PRIVATE)
    private val file get() = File(context.dataDir, "shared_prefs/token_test.xml")

    @After
    fun tearDown() {
        folder.repair()
    }

    @Test
    fun aTokenIsCreatedOnceAndKept() {
        val token = ReminderIntentToken { preferences }
        val created = checkNotNull(token.current())
        assertEquals(32, created.length)
        assertEquals(created, token.current())
        assertTrue("on the disk", file.readText().contains(created))
        assertEquals("a later process", created, ReminderIntentToken { preferences }.current())
        assertTrue(token.matches(created))
        assertFalse(token.matches(created.reversed()))
        assertFalse(token.matches(null))
    }

    @Test
    fun aTokenThatCouldNotBeStoredIsNotHandedOutAndTriedAgain() {
        folder.breakIt()
        // Writing fails indeed, and Android keeps the value in memory.
        val probe = preferences.edit().putString("probe", "x")
        assertFalse(probe.commit())
        assertEquals("x", preferences.getString("probe", null))

        val token = ReminderIntentToken { preferences }
        assertNull(token.current())
        assertNull("not cached and not read back from memory: tried again", token.current())
        assertFalse(token.matches("0".repeat(32)))
        assertFalse("not in memory either", preferences.contains("token"))

        // Writing works again: the next call creates, stores and keeps one.
        folder.repair()
        val created = checkNotNull(token.current())
        assertEquals(created, preferences.getString("token", null))
        assertTrue("on the disk", file.readText().contains(created))
        assertEquals(created, token.current())
        assertTrue(token.matches(created))
    }
}
