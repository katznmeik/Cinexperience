package io.github.katznmeik.cinexperience.core.data.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import io.github.katznmeik.cinexperience.core.model.MembershipCard
import io.github.katznmeik.cinexperience.core.model.MembershipCardText
import io.github.katznmeik.cinexperience.core.model.QrModules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The owner's membership card (spec §4.8, v1.5): at most one. It stays on the phone:
 * not in the database, the backup file or Auto Backup (`membership_card.xml` is
 * excluded in the app's backup rules). Implementations never throw: a card that
 * can't be read counts as none, and a failed write returns false and changes nothing.
 */
interface MembershipCardStore {
    /** The stored card (`null` = none), then every change. */
    val card: Flow<MembershipCard?>

    /** Stores [card] in place of the previous one; false if it couldn't be stored. */
    suspend fun save(card: MembershipCard): Boolean

    /** Removes the card; false if it couldn't be removed (it is then kept). */
    suspend fun remove(): Boolean
}

/**
 * [MembershipCardStore] in shared preferences (`membership_card.xml`): the three texts
 * and the QR code as [QrModules.encode] text. A card is read only whole: a missing or
 * damaged QR code, or a text that isn't clean or too long, reads as no card. Reads
 * and writes run on [ioDispatcher]; the card is read once, on first use, and then
 * kept in memory (the app has one store per process).
 */
class SharedPreferencesMembershipCardStore(
    private val openPreferences: () -> SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MembershipCardStore {

    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : this(
        { context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) },
        ioDispatcher,
    )

    private val prefs: SharedPreferences by lazy(openPreferences)

    private val mutex = Mutex()

    /** [Unread] until read, then the card or `null` in a [Loaded]. */
    private val current = MutableStateFlow<State>(Unread)

    private sealed interface State
    private data object Unread : State
    private data class Loaded(val card: MembershipCard?) : State

    override val card: Flow<MembershipCard?> = flow {
        if (current.value == Unread) mutex.withLock { load() }
        emitAll(current.filter { it is Loaded }.map { (it as Loaded).card })
    }.distinctUntilChanged()

    override suspend fun save(card: MembershipCard): Boolean = write(card) { editor ->
        editor.clear()
        card.name?.let { editor.putString(KEY_NAME, it) }
        card.memberNumber?.let { editor.putString(KEY_MEMBER_NUMBER, it) }
        card.membershipType?.let { editor.putString(KEY_MEMBERSHIP_TYPE, it) }
        editor.putString(KEY_QR_MODULES, card.qrModules.encode())
    }

    override suspend fun remove(): Boolean = write(null) { editor -> editor.clear() }

    private suspend fun write(after: MembershipCard?, change: (SharedPreferences.Editor) -> Unit): Boolean = mutex.withLock {
        val stored = withContext(ioDispatcher) {
            try {
                // commit (on the IO dispatcher): its result tells whether the card was stored,
                // which the KTX edit { } doesn't return.
                @SuppressLint("UseKtx")
                val editor = prefs.edit()
                change(editor)
                val committed = editor.commit()
                committed
            } catch (e: Exception) {
                false
            }
        }
        if (stored) current.value = Loaded(after)
        stored
    }

    /** Reads the card into memory if it isn't yet. Called under [mutex]. */
    private suspend fun load() {
        if (current.value == Unread) current.value = Loaded(withContext(ioDispatcher) { read() })
    }

    private fun read(): MembershipCard? = try {
        val qr = prefs.getString(KEY_QR_MODULES, null)?.let(QrModules::decodeOrNull)
        val name = prefs.getString(KEY_NAME, null)
        val number = prefs.getString(KEY_MEMBER_NUMBER, null)
        val type = prefs.getString(KEY_MEMBERSHIP_TYPE, null)
        if (
            qr == null ||
            !MembershipCardText.isValid(name, MembershipCard.MAX_NAME_LENGTH) ||
            !MembershipCardText.isValid(number, MembershipCard.MAX_MEMBER_NUMBER_LENGTH) ||
            !MembershipCardText.isValid(type, MembershipCard.MAX_MEMBERSHIP_TYPE_LENGTH)
        ) {
            null
        } else {
            MembershipCard(name, number, type, qr)
        }
    } catch (e: Exception) {
        // Another type under a key (getString throws), or the file can't be read.
        null
    }

    companion object {
        /** Excluded from Auto Backup (spec §8): see the app's backup rules. */
        const val PREFERENCES_NAME: String = "membership_card"
        internal const val KEY_NAME = "name"
        internal const val KEY_MEMBER_NUMBER = "member_number"
        internal const val KEY_MEMBERSHIP_TYPE = "membership_type"
        internal const val KEY_QR_MODULES = "qr_modules"
    }
}
