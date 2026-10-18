package io.github.katznmeik.cinexperience.reminders

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A random secret of this installation that the reminders' notification intents carry
 * ([ReminderIntents]): `MainActivity` is exported, so another app could send it the same
 * actions; only an intent with this token opens a screen. The intents are immutable
 * `PendingIntent`s, whose extras no other app can read or change. Kept in
 * `reminder_intents.xml`, which Auto Backup leaves out (phone-local: a restored phone gets
 * a new token; notifications from before simply open nothing).
 */
class ReminderIntentToken(private val openPreferences: () -> SharedPreferences) {

    constructor(context: Context) : this({
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    })

    private val prefs: SharedPreferences by lazy(openPreferences)

    @Volatile
    private var cached: String? = null

    /** The token, created on first use; `null` only if it couldn't be stored (no notification then opens a screen). */
    @Synchronized
    fun current(): String? {
        cached?.let { return it }
        return try {
            val stored = prefs.getString(KEY, null)?.takeIf { it.length == LENGTH }
            val token = stored ?: newToken().also { created -> prefs.edit(commit = true) { putString(KEY, created) } }
            token.also { cached = it }
        } catch (e: Throwable) {
            null
        }
    }

    /** Whether [candidate] is the token (compared in constant time); false while there is none. */
    fun matches(candidate: String?): Boolean {
        candidate ?: return false
        val token = current() ?: return false
        return MessageDigest.isEqual(token.toByteArray(), candidate.toByteArray())
    }

    private fun newToken(): String {
        val bytes = ByteArray(BYTES).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    }

    companion object {
        const val PREFERENCES_NAME: String = "reminder_intents"
        private const val KEY = "token"
        private const val BYTES = 16
        private const val LENGTH = BYTES * 2
    }
}
