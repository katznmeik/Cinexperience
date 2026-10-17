package io.github.katznmeik.cinexperience.ui

import io.github.katznmeik.cinexperience.feature.program.ProgramFilterSettings
import io.github.katznmeik.cinexperience.feature.program.ProgramFilterStore

/**
 * The Program tab's filters in memory, for [FakeAppContainer]. (feature:program's
 * tests have their own, with write counting; test sources aren't shared between
 * modules, and core:testing can't depend on a feature.)
 */
internal class InMemoryProgramFilterStore : ProgramFilterStore {
    private var settings = ProgramFilterSettings()

    override suspend fun read(): ProgramFilterSettings = settings

    override suspend fun write(settings: ProgramFilterSettings): Boolean {
        this.settings = settings
        return true
    }
}
