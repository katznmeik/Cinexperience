package io.github.katznmeik.cinexperience.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.katznmeik.cinexperience.core.data.settings.SharedPreferencesMembershipCardStore
import io.github.katznmeik.cinexperience.core.model.MembershipCard
import io.github.katznmeik.cinexperience.core.model.QrModules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The membership card in `membership_card.xml` (spec §4.8): none until saved, saved
 * and removed whole, every change reaches the flow, damaged values read as no card,
 * and a failed write changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MembershipCardStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences =
        context.getSharedPreferences(SharedPreferencesMembershipCardStore.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val qr = QrModules.of(21) { row, column -> (row * column) % 5 == 1 }
    private val card = MembershipCard("Alex Example", "TEST-0042", "Yorck Unlimited", qr)

    private fun store() = SharedPreferencesMembershipCardStore(context, Dispatchers.Unconfined)

    @Test
    fun noCardUntilSaved() = runTest {
        assertNull(store().card.first())
    }

    @Test
    fun savesReadsBackAndRemoves() = runTest {
        val store = store()
        val seen = async { store.card.take(4).toList() }
        yield()
        assertTrue(store.save(card))
        yield()
        val other = MembershipCard(null, null, null, QrModules.of(25) { row, _ -> row % 2 == 0 })
        assertTrue(store.save(other))
        yield()
        assertTrue(store.remove())

        assertEquals(listOf(null, card, other, null), seen.await())
        // A new store (the next process) reads what is stored.
        assertNull(store().card.first())
    }

    @Test
    fun aNewStoreReadsTheSavedCard() = runTest {
        assertTrue(store().save(card))

        assertEquals(card, store().card.first())
    }

    @Test
    fun savingReplacesEveryValue() = runTest {
        assertTrue(store().save(card))
        val withoutTexts = MembershipCard(null, null, null, qr)
        assertTrue(store().save(withoutTexts))

        assertEquals(withoutTexts, store().card.first())
        assertFalse(prefs.contains("name"))
    }

    @Test
    fun damagedValuesReadAsNoCard() = runTest {
        fun storedAs(edit: SharedPreferences.Editor.() -> Unit): MembershipCard? {
            prefs.edit().clear().putString("qr_modules", qr.encode()).apply(edit).commit()
            return kotlinx.coroutines.runBlocking { store().card.first() }
        }

        assertEquals(MembershipCard(null, null, null, qr), storedAs {})
        assertNull(storedAs { remove("qr_modules") })
        assertNull(storedAs { putString("qr_modules", "21:not base64") })
        assertNull(storedAs { putInt("qr_modules", 3) })
        assertNull(storedAs { putString("name", " Alex") })
        assertNull(storedAs { putString("member_number", "1".repeat(MembershipCard.MAX_MEMBER_NUMBER_LENGTH + 1)) })
        assertNull(storedAs { putBoolean("membership_type", true) })
    }

    @Test
    fun aFailedWriteChangesNothing() = runTest {
        assertTrue(store().save(card))
        val failing = SharedPreferencesMembershipCardStore({ FailingPreferences(prefs) }, Dispatchers.Unconfined)
        assertEquals(card, failing.card.first())

        assertFalse(failing.remove())
        assertFalse(failing.save(MembershipCard(null, null, null, qr)))

        assertEquals(card, failing.card.first())
        assertEquals(card, store().card.first())
    }

    /** Reads from [delegate]; every commit fails. */
    private class FailingPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by delegate.edit() {
            override fun commit(): Boolean = false
        }
    }
}
