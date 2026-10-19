package io.github.katznmeik.cinexperience.reminders

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** [real], except that the next [failures] commits fail and store nothing (e.g. a full disk). */
internal class FailingCommits(private val real: SharedPreferences, var failures: Int) : SharedPreferences by real {
    /** Every commit tried. */
    var commits: Int = 0
        private set

    override fun edit(): SharedPreferences.Editor {
        val editor = real.edit()
        return object : SharedPreferences.Editor by editor {
            override fun commit(): Boolean {
                commits++
                if (failures > 0) {
                    failures--
                    return false
                }
                return editor.commit()
            }
        }
    }
}

/**
 * The notification intents' token ([ReminderIntentToken]): created once, stored, the
 * same in a later process; one that couldn't be stored is never handed out or cached
 * (`null`, tried again next time), so no notification carries a token MainActivity
 * doesn't know.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderIntentTokenTest {

    private val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("token_test", Context.MODE_PRIVATE)

    @Test
    fun aTokenIsCreatedOnceAndKept() {
        val token = ReminderIntentToken { preferences }
        val created = checkNotNull(token.current())
        assertEquals(32, created.length)
        assertEquals(created, token.current())
        assertEquals("a later process", created, ReminderIntentToken { preferences }.current())
        assertTrue(token.matches(created))
        assertFalse(token.matches(created.reversed()))
        assertFalse(token.matches(null))
    }

    @Test
    fun aTokenThatCouldNotBeStoredIsNotHandedOutAndTriedAgain() {
        val failing = FailingCommits(preferences, failures = 3)
        val token = ReminderIntentToken { failing }
        assertNull(token.current())
        assertNull("not cached: tried again", token.current())
        assertFalse(token.matches("0".repeat(32)))
        assertEquals(3, failing.commits)
        assertFalse("nothing stored", preferences.contains("token"))

        // Storing works again: the next call creates, stores and keeps one.
        val created = checkNotNull(token.current())
        assertEquals(created, preferences.getString("token", null))
        assertEquals(created, token.current())
        assertEquals("kept, not stored again", 4, failing.commits)
        assertTrue(token.matches(created))
    }
}
