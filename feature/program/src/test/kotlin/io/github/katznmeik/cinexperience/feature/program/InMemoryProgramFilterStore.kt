package io.github.katznmeik.cinexperience.feature.program

/** An in-memory [ProgramFilterStore] for the feature's tests. */
class InMemoryProgramFilterStore(var settings: ProgramFilterSettings = ProgramFilterSettings()) : ProgramFilterStore {
    var writes: Int = 0
        private set

    /** While true, writes fail (return false) and store nothing. */
    var failWrites: Boolean = false

    override suspend fun read(): ProgramFilterSettings = settings

    override suspend fun write(settings: ProgramFilterSettings): Boolean {
        writes++
        if (failWrites) return false
        this.settings = settings
        return true
    }
}
