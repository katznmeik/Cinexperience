package io.github.katznmeik.cinexperience.core.data.contract

import io.github.katznmeik.cinexperience.core.model.SeatOverride
import io.github.katznmeik.cinexperience.core.model.Visit
import io.github.katznmeik.cinexperience.core.testing.FakeCinexRepository
import kotlinx.coroutines.flow.update

/** [RepositoryContract] on the in-memory fake of core:testing, on an empty store per test. */
internal class FakeRepositoryContractTest : RepositoryContract() {

    private val fake = FakeCinexRepository()

    private var nextVisitId = 900_000L

    override fun createRepositories(): Repositories = Repositories(fake, fake, fake, fake, fake)

    override suspend fun insertUnchecked(visit: Visit) {
        fake.store.update { it.copy(visits = it.visits + visit.copy(id = nextVisitId++)) }
    }

    override suspend fun insertUnchecked(override: SeatOverride) {
        fake.store.update { it.copy(overrides = it.overrides + override) }
    }
}
