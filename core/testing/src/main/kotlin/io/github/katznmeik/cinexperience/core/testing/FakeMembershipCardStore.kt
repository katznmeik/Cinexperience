package io.github.katznmeik.cinexperience.core.testing

import io.github.katznmeik.cinexperience.core.data.settings.MembershipCardStore
import io.github.katznmeik.cinexperience.core.model.MembershipCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [MembershipCardStore]; [failWrites] makes saving and removing fail (nothing changes). */
class FakeMembershipCardStore(initial: MembershipCard? = null) : MembershipCardStore {

    val current: MutableStateFlow<MembershipCard?> = MutableStateFlow(initial)

    var failWrites: Boolean = false

    /** How many writes succeeded. */
    var writes: Int = 0
        private set

    override val card: Flow<MembershipCard?> get() = current

    override suspend fun save(card: MembershipCard): Boolean = write(card)

    override suspend fun remove(): Boolean = write(null)

    private fun write(after: MembershipCard?): Boolean {
        if (failWrites) return false
        current.value = after
        writes++
        return true
    }
}
